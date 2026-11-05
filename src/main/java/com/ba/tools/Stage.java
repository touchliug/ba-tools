package com.ba.tools;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 阶段识别 v2 —— 修正 v1 的四个硬伤：
 *
 * <ol>
 *   <li><b>阈值改为自身历史 z-score</b>：不再用"量比 &gt; 1.5"这种全市场绝对值，
 *       而是"今天的量比相对它自己过去 90 天的均值偏离几个标准差"。</li>
 *   <li><b>多周期一致性</b>：日线判一次、7 天趋势判一次，冲突时明确标注，不硬给结论。</li>
 *   <li><b>基准对比</b>：同时算相对 BTC 的超额收益，区分"币弱"与"大盘弱"。</li>
 *   <li><b>输出证伪条件</b>：每个判定给出"什么情况下这个判断算错"，让结论可检验。</li>
 * </ol>
 *
 * <p>「match」是规则匹配度（0-100 的加权和），<b>不是概率</b>；真实胜率要靠 {@link Backtest} 标定。
 */
final class Stage {

    private static final long DAY = 86_400_000L;
    private static final int Z_WINDOW = 90;   // z-score 回看天数
    private static final int LOOKBACK = 180;  // 加载天数

    private Stage() {}

    // ---------------- 数据结构 ----------------

    /** 一天的对齐数据；缺失字段为 NaN。 */
    record Day(long t, double close, double high, double low, double vol,
               double oi, double funding, double topPos, double retail) {}

    /** 单币在某一天的特征（含 z-score）。 */
    record Feat(String symbol, int idx, long t, double close,
                double ret, double ret7, double retZ, double volRatio, double volRatioZ,
                double range, double rangeZ, double oiChg, double oiChgZ,
                double drawdown, double oiFromPeak, double funding, double fundingZ,
                double topPos, double retail, double excess7, double dataDays) {}

    record Verdict(String stage, int match, boolean consistent, List<String> evidence, String falsify) {}

    // ---------------- 主流程 ----------------

    static void run(Args a) throws Exception {
        int top = a.getInt("top", 30);
        int minMatch = a.getInt("min-match", a.getInt("min-score", 60));
        String only = a.get("symbol", null);

        Map<String, List<Day>> series = load(a);
        if (series.isEmpty()) { System.out.println("没有数据"); return; }

        double[] btc = btcReturns(series);
        System.out.printf("加载 %d 天日线, %d 个币, BTC 7d %+.1f%%%n%n", LOOKBACK, series.size(), btc[1] * 100);

        List<Object[]> rows = new ArrayList<>();
        for (var e : series.entrySet()) {
            Feat f = feature(e.getKey(), e.getValue(), btc);
            if (f == null) continue;
            Verdict v = classify(f);
            if (only != null && e.getKey().equalsIgnoreCase(only)) { printDetail(f, v); return; }
            rows.add(new Object[]{e.getKey(), f, v});
        }
        if (only != null) { System.out.println("没有该币或数据不足: " + only); return; }

        rows.sort(Comparator.comparingInt((Object[] r) -> ((Verdict) r[2]).match()).reversed());
        System.out.printf("%-13s %-6s %-5s %-5s %8s %8s %8s %8s %7s %6s%n",
                "币种", "阶段", "匹配", "一致", "24h%", "超额7d", "量比z", "OI变化z", "费率z", "天数");
        int shown = 0;
        for (Object[] r : rows) {
            Feat f = (Feat) r[1];
            Verdict v = (Verdict) r[2];
            if (v.match() < minMatch) continue;
            if (shown++ >= top) break;
            System.out.printf("%-13s %-6s %-5d %-5s %+8.1f %+8.1f %+8.2f %+8.2f %+7.2f %6.0f%n",
                    f.symbol(), v.stage(), v.match(), v.consistent() ? "是" : "**冲突**",
                    f.ret() * 100, f.excess7() * 100, f.volRatioZ(), f.oiChgZ(), f.fundingZ(), f.dataDays());
        }
        System.out.printf("%n共 %d 个币有足够数据，列出匹配度 >= %d 的前 %d 个%n", rows.size(), minMatch, top);
        System.out.println("注：match 是规则匹配度，不是胜率；用 `backtest` 看各阶段的历史表现。");
    }

    // ---------------- 数据加载 ----------------

    static Map<String, List<Day>> load(Args a) throws Exception {
        long since = System.currentTimeMillis() - (long) LOOKBACK * DAY;

        // 1d K 线
        Map<String, List<double[]>> k = new HashMap<>();   // t, close, high, low, vol
        try (Connection cn = Db.open(a);
             PreparedStatement ps = cn.prepareStatement(
                     "SELECT symbol, open_time, CAST(`close` AS DECIMAL(30,10)), CAST(high AS DECIMAL(30,10)), "
                   + "CAST(low AS DECIMAL(30,10)), CAST(quote_asset_volume AS DECIMAL(30,2)) "
                   + "FROM klines WHERE `interval`='1d' AND open_time >= ? ORDER BY symbol, open_time")) {
            ps.setLong(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    k.computeIfAbsent(rs.getString(1), x -> new ArrayList<>())
                            .add(new double[]{rs.getLong(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)});
                }
            }
        }

        Map<String, Map<Long, Double>> oi = new HashMap<>();
        try (Connection cn = Db.open(a);
             PreparedStatement ps = cn.prepareStatement(
                     "SELECT symbol, `timestamp`, CAST(sum_open_interest AS DECIMAL(30,2)) "
                   + "FROM open_interest WHERE period='1d' AND `timestamp` >= ?")) {
            ps.setLong(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Object v = rs.getObject(3);
                    if (v == null) continue;
                    oi.computeIfAbsent(rs.getString(1), x -> new HashMap<>())
                            .put(rs.getLong(2), ((Number) v).doubleValue());
                }
            }
        }

        Map<String, Map<Long, double[]>> fr = new HashMap<>();   // 日 -> {sum, count}
        try (Connection cn = Db.open(a);
             PreparedStatement ps = cn.prepareStatement(
                     "SELECT symbol, funding_time, CAST(funding_rate AS DECIMAL(20,10)) "
                   + "FROM funding_rate WHERE funding_time >= ?")) {
            ps.setLong(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long dayKey = rs.getLong(2) / DAY * DAY;
                    double[] acc = fr.computeIfAbsent(rs.getString(1), x -> new HashMap<>())
                            .computeIfAbsent(dayKey, x -> new double[2]);
                    acc[0] += rs.getDouble(3);
                    acc[1]++;
                }
            }
        }

        Map<String, Map<Long, double[]>> lsr = new HashMap<>();  // 日 -> {topPos, retail}
        try (Connection cn = Db.open(a);
             PreparedStatement ps = cn.prepareStatement(
                     "SELECT symbol, `timestamp`, ratio_type, CAST(long_short_ratio AS DECIMAL(20,6)) "
                   + "FROM long_short_ratio WHERE period='1h' AND `timestamp` >= ? "
                   + "AND ratio_type IN ('TOP_POSITION','GLOBAL_ACCOUNT')")) {
            ps.setLong(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long dayKey = rs.getLong(2) / DAY * DAY;
                    double[] acc = lsr.computeIfAbsent(rs.getString(1), x -> new HashMap<>())
                            .computeIfAbsent(dayKey, x -> new double[]{Double.NaN, Double.NaN});
                    if ("TOP_POSITION".equals(rs.getString(3))) acc[0] = rs.getDouble(4);
                    else acc[1] = rs.getDouble(4);
                }
            }
        }

        Map<String, List<Day>> out = new LinkedHashMap<>();
        for (var e : k.entrySet()) {
            String sym = e.getKey();
            List<double[]> bars = e.getValue();
            if (bars.size() < 40) continue;
            List<Day> days = new ArrayList<>(bars.size());
            for (double[] b : bars) {
                long dayKey = (long) b[0] / DAY * DAY;
                Double oiv = oi.getOrDefault(sym, Map.of()).get(dayKey);
                double[] facc = fr.getOrDefault(sym, Map.of()).getOrDefault(dayKey, null);
                double[] lacc = lsr.getOrDefault(sym, Map.of()).getOrDefault(dayKey, null);
                days.add(new Day((long) b[0], b[1], b[2], b[3], b[4],
                        oiv == null ? Double.NaN : oiv,
                        facc == null ? Double.NaN : facc[0] / facc[1],
                        lacc == null ? Double.NaN : lacc[0],
                        lacc == null ? Double.NaN : lacc[1]));
            }
            out.put(sym, days);
        }
        return out;
    }

    /** BTC 的 1d / 7d 收益，用作基准。 */
    static double[] btcReturns(Map<String, List<Day>> series) {
        List<Day> b = series.get("BTCUSDT");
        if (b == null || b.size() < 8) return new double[]{0, 0};
        int i = b.size() - 1;
        double r1 = b.get(i - 1).close() > 0 ? b.get(i).close() / b.get(i - 1).close() - 1 : 0;
        double r7 = b.get(i - 7).close() > 0 ? b.get(i).close() / b.get(i - 7).close() - 1 : 0;
        return new double[]{r1, r7};
    }

    // ---------------- 特征 ----------------

    static Feat feature(String symbol, List<Day> d, double[] btc) {
        return featureAt(symbol, d, d.size() - 1, btc);
    }

    /** 计算第 i 天的特征（只用 i 之前的数据，回测时不会引入未来信息）。 */
    static Feat featureAt(String symbol, List<Day> d, int i, double[] btc) {
        int n = d.size();
        if (i < 40 || i >= n) return null;
        Day today = d.get(i);

        double prev = d.get(i - 1).close();
        double ret = prev > 0 ? today.close() / prev - 1 : 0;
        double ret7 = d.get(i - 7).close() > 0 ? today.close() / d.get(i - 7).close() - 1 : 0;

        // 量比 = 今日 / 前 7 日均量
        double volAvg = 0;
        for (int j = i - 7; j < i; j++) volAvg += d.get(j).vol();
        volAvg /= 7;
        double volRatio = volAvg > 0 ? today.vol() / volAvg : 1;

        double range = today.low() > 0 ? (today.high() - today.low()) / today.low() : 0;

        double oiChg = Double.NaN;
        if (!Double.isNaN(today.oi()) && !Double.isNaN(d.get(i - 1).oi()) && d.get(i - 1).oi() > 0) {
            oiChg = today.oi() / d.get(i - 1).oi() - 1;
        }

        double hi30 = 0, oiPeak = 0;
        for (int j = Math.max(0, i - 30); j <= i; j++) {
            hi30 = Math.max(hi30, d.get(j).high());
            if (!Double.isNaN(d.get(j).oi())) oiPeak = Math.max(oiPeak, d.get(j).oi());
        }
        double drawdown = hi30 > 0 ? today.close() / hi30 - 1 : 0;
        double oiFromPeak = (oiPeak > 0 && !Double.isNaN(today.oi())) ? today.oi() / oiPeak - 1 : Double.NaN;

        // z-score：今日值相对"过去 90 天（不含今日）"的均值/标准差
        double retZ = z(d, i, x -> x.close());
        double volRatioZ = zRatio(d, i);
        double rangeZ = z(d, i, x -> x.low() > 0 ? (x.high() - x.low()) / x.low() : 0);
        double oiChgZ = zOi(d, i);
        double fundingZ = z(d, i, x -> x.funding());

        double excess7 = ret7 - btc[1];

        return new Feat(symbol, i, today.t(), today.close(), ret, ret7, retZ, volRatio, volRatioZ, range, rangeZ,
                oiChg, oiChgZ, drawdown, oiFromPeak, today.funding(), fundingZ,
                today.topPos(), today.retail(), excess7, n);
    }

    private interface Fn { double get(Day d); }

    private static double z(List<Day> d, int i, Fn fn) {
        int from = Math.max(0, i - Z_WINDOW), to = i;
        double sum = 0, sq = 0;
        int c = 0;
        for (int j = from; j < to; j++) {
            double v = fn.get(d.get(j));
            if (Double.isNaN(v)) continue;
            sum += v; sq += v * v; c++;
        }
        if (c < 20) return 0;
        double mean = sum / c;
        double var = sq / c - mean * mean;
        if (var <= 0) return 0;
        double sd = Math.sqrt(var);
        double cur = fn.get(d.get(i));
        return Double.isNaN(cur) || sd == 0 ? 0 : (cur - mean) / sd;
    }

    /** 量比的 z：先在窗口内逐日算量比，再标准化。 */
    private static double zRatio(List<Day> d, int i) {
        int from = Math.max(8, i - Z_WINDOW), to = i;
        double sum = 0, sq = 0;
        int c = 0;
        double cur = ratioAt(d, i);
        for (int j = from; j < to; j++) {
            double v = ratioAt(d, j);
            if (Double.isNaN(v)) continue;
            sum += v; sq += v * v; c++;
        }
        if (c < 20) return 0;
        double mean = sum / c;
        double var = sq / c - mean * mean;
        if (var <= 0) return 0;
        return (cur - mean) / Math.sqrt(var);
    }

    private static double ratioAt(List<Day> d, int i) {
        if (i < 8) return Double.NaN;
        double avg = 0;
        for (int j = i - 7; j < i; j++) avg += d.get(j).vol();
        avg /= 7;
        return avg > 0 ? d.get(i).vol() / avg : Double.NaN;
    }

    private static double zOi(List<Day> d, int i) {
        int from = Math.max(1, i - Z_WINDOW), to = i;
        double sum = 0, sq = 0;
        int c = 0;
        double cur = oiChgAt(d, i);
        for (int j = from; j < to; j++) {
            double v = oiChgAt(d, j);
            if (Double.isNaN(v)) continue;
            sum += v; sq += v * v; c++;
        }
        if (c < 20) return 0;
        double mean = sum / c;
        double var = sq / c - mean * mean;
        if (var <= 0) return 0;
        return (cur - mean) / Math.sqrt(var);
    }

    private static double oiChgAt(List<Day> d, int i) {
        if (i < 1 || Double.isNaN(d.get(i).oi()) || Double.isNaN(d.get(i - 1).oi()) || d.get(i - 1).oi() <= 0) return Double.NaN;
        return d.get(i).oi() / d.get(i - 1).oi() - 1;
    }

    // ---------------- 判定 ----------------

    static Verdict classify(Feat f) {
        List<String> ev = new ArrayList<>();
        int dump = 0, markup = 0, dist = 0, test = 0, accum = 0;

        // 砸盘：暴跌 + 仓位骤减
        if (f.retZ() < -2.0) { dump += 35; ev.add(String.format("日跌幅 z=%.1f", f.retZ())); }
        if (f.oiChgZ() < -1.5) { dump += 30; ev.add(String.format("OI 降幅 z=%.1f", f.oiChgZ())); }
        if (f.drawdown() < -0.25) dump += 25;

        // 拉升：涨幅显著 + 仓位同增 + 放量
        if (f.retZ() > 1.5) { markup += 30; ev.add(String.format("日涨幅 z=%.1f", f.retZ())); }
        if (f.oiChgZ() > 1.0) { markup += 30; ev.add(String.format("OI 增幅 z=%.1f", f.oiChgZ())); }
        if (f.volRatioZ() > 1.0) markup += 20;
        if (f.drawdown() < -0.40) markup -= 25;   // 深跌反弹不算拉升

        // 派发：贴近高点 + 仓位从峰值回落 + 缩量
        if (f.drawdown() > -0.08) dist += 30;
        if (!Double.isNaN(f.oiFromPeak()) && f.oiFromPeak() < -0.05) { dist += 25; ev.add(String.format("OI 距峰 %.1f%%", f.oiFromPeak() * 100)); }
        if (f.volRatioZ() < -0.8) { dist += 25; ev.add(String.format("缩量 z=%.1f", f.volRatioZ())); }
        if (f.funding() > 0.0002) dist += 20;

        // 试盘：振幅异常但净变动小
        if (f.rangeZ() > 2.0) { test += 30; ev.add(String.format("振幅 z=%.1f", f.rangeZ())); }
        if (Math.abs(f.retZ()) < 0.8) test += 25;
        if (f.volRatioZ() > 1.5) test += 25;
        if (Math.abs(f.oiChgZ()) > 1.5) test += 20;

        // 吸筹：横盘 + 地量 + 仓位仍在（不是离场）
        if (Math.abs(f.retZ()) < 0.6) accum += 25;
        if (f.volRatioZ() < -1.0) { accum += 25; ev.add(String.format("地量 z=%.1f", f.volRatioZ())); }
        if (!Double.isNaN(f.oiFromPeak()) && f.oiFromPeak() > -0.10) accum += 20;
        if (Math.abs(f.funding()) < 0.0002) accum += 15;
        if (f.drawdown() < -0.20) accum += 15;

        String[] names = {"砸盘", "拉升", "派发", "试盘", "吸筹"};
        int[] scores = {dump, markup, dist, test, accum};
        int best = 0;
        String stage = "冷却";
        for (int i = 0; i < names.length; i++) {
            if (scores[i] > best) { best = scores[i]; stage = names[i]; }
        }
        if (best < 50) { stage = "冷却"; best = Math.max(best, 30); }

        // 多周期一致性：7 天趋势是否与日线结论同向
        boolean consistent = true;
        if ("拉升".equals(stage) && f.ret7() < -0.05) consistent = false;
        if ("砸盘".equals(stage) && f.ret7() > 0.08) consistent = false;
        if ("吸筹".equals(stage) && f.ret7() < -0.15) consistent = false;

        return new Verdict(stage, Math.min(100, best), consistent, ev, falsify(stage, f));
    }

    /** 证伪条件：什么情况下这个判断算错。 */
    static String falsify(String stage, Feat f) {
        return switch (stage) {
            case "拉升" -> "若放量站不上近 30 日高点，或 OI 转向下降，则拉升判断失效";
            case "派发" -> "若放量突破近 30 日高点且 OI 同步上升，则派发判断失效";
            case "砸盘" -> "若 3 日内收复前一日跌幅一半且 OI 回升，则砸盘判断失效";
            case "吸筹" -> "若放量跌破近 30 日低点，则吸筹判断失效";
            case "试盘" -> "若振幅收敛且 OI 回落至常态，则试盘结束";
            default -> "无明确结构，不需要证伪";
        };
    }

    static void printDetail(Feat f, Verdict v) {
        System.out.println("币种: " + f.symbol() + "   数据: " + (int) f.dataDays() + " 天");
        System.out.printf("收盘 %.6f   日涨跌 %+.1f%%   7 日 %+.1f%%   相对 BTC 超额(7d) %+.1f%%%n",
                f.close(), f.ret() * 100, f.ret7() * 100, f.excess7() * 100);
        System.out.printf("z-score: 涨跌 %+.2f   量比 %+.2f   振幅 %+.2f   OI 变化 %+.2f   费率 %+.2f%n",
                f.retZ(), f.volRatioZ(), f.rangeZ(), f.oiChgZ(), f.fundingZ());
        System.out.printf("距 30 日高点 %+.1f%%   OI 距峰 %+.1f%%   大户比 %s   散户比 %s%n",
                f.drawdown() * 100, f.oiFromPeak() * 100,
                fmtNa(f.topPos()), fmtNa(f.retail()));
        System.out.println();
        System.out.printf("判定: %s   匹配度 %d/100%s%n", v.stage(), v.match(), v.consistent() ? "" : "   [多周期冲突]");
        System.out.println("证据:");
        if (v.evidence().isEmpty()) System.out.println("  · 无明显异常项");
        else v.evidence().forEach(e -> System.out.println("  · " + e));
        System.out.println("证伪: " + v.falsify());
    }

    private static String fmtNa(double v) { return Double.isNaN(v) ? "--" : String.format(Locale.ROOT, "%.3f", v); }
}
