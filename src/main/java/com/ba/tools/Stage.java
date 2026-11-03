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
import java.util.TreeMap;

/**
 * 阶段识别：判断一个币当前处在「吸筹 / 试盘 / 拉升 / 派发 / 砸盘 / 冷却」的哪一段。
 *
 * <p>思路：把每个阶段翻译成可量化特征（价格斜率、OI 斜率、量比、资金费率、散户比背离），
 * 每个阶段给一套加权打分，取最高分作为当前阶段，并输出触发它的证据。
 *
 * <p>特征全部来自 ba-sync 写入的表，不做任何外部请求。
 */
final class Stage {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    private Stage() {}

    /** 单币特征。 */
    record Feature(
            String symbol,
            double close,
            double ret24,        // 24h 涨跌 %
            double ret7d,        // 7d 涨跌 %
            double volRatio,     // 24h 成交额 / 前 6 天日均
            double oiChg24,      // OI(币数) 24h 变化 %
            double oiChg7d,
            double oiFromPeak,   // 当前 OI 距 14 天峰值 %
            double drawdown,     // 现价距 14 天最高 %
            double range24,      // 24h 振幅 %
            double funding,      // 最新资金费率（小数）
            double funding3d,    // 近 3 天均值
            double retailDelta,  // 散户账户比 72h 变化
            double topPos,       // 大户持仓比
            int bars) {

        boolean ok() { return bars >= 72; }
    }

    /** 阶段判定结果。 */
    record Verdict(String stage, int score, List<String> evidence) {}

    static void run(Args a) throws Exception {
        int days = a.getInt("days", 7);
        int top = a.getInt("top", 30);
        int minScore = a.getInt("min-score", 40);
        String only = a.get("symbol", null);

        long since = System.currentTimeMillis() - (long) (days + 7) * DAY;

        Map<String, List<double[]>> klines = new HashMap<>();   // t, close, high, low, vol
        Map<String, TreeMap<Long, Double>> oi = new HashMap<>();
        Map<String, TreeMap<Long, Double>> lsr = new HashMap<>();   // key: symbol|type

        try (Connection cn = Db.open(a)) {
            System.out.printf("加载 %d 天小时数据...%n", days + 7);
            try (PreparedStatement ps = cn.prepareStatement(
                    "SELECT symbol, open_time, CAST(`close` AS DECIMAL(30,12)), CAST(high AS DECIMAL(30,12)), "
                  + "CAST(low AS DECIMAL(30,12)), CAST(quote_asset_volume AS DECIMAL(30,2)) "
                  + "FROM klines WHERE `interval`='1h' AND open_time >= ? ORDER BY symbol, open_time")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        klines.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                                .add(new double[]{rs.getLong(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)});
                    }
                }
            }
            try (PreparedStatement ps = cn.prepareStatement(
                    "SELECT symbol, `timestamp`, CAST(sum_open_interest AS DECIMAL(30,2)) "
                  + "FROM open_interest WHERE period='1h' AND `timestamp` >= ?")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    Object v;
                    while (rs.next()) {
                        v = rs.getObject(3);
                        if (v == null) continue;
                        oi.computeIfAbsent(rs.getString(1), k -> new TreeMap<>())
                                .put(rs.getLong(2), ((Number) v).doubleValue());
                    }
                }
            }
            try (PreparedStatement ps = cn.prepareStatement(
                    "SELECT symbol, ratio_type, `timestamp`, CAST(long_short_ratio AS DECIMAL(20,6)) "
                  + "FROM long_short_ratio WHERE period='1h' AND `timestamp` >= ? "
                  + "AND ratio_type IN ('TOP_POSITION','GLOBAL_ACCOUNT')")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lsr.computeIfAbsent(rs.getString(1) + "|" + rs.getString(2), k -> new TreeMap<>())
                                .put(rs.getLong(3), rs.getDouble(4));
                    }
                }
            }
            System.out.printf("klines=%d 币, OI=%d 币%n", klines.size(), oi.size());

            Map<String, TreeMap<Long, Double>> funding = new HashMap<>();
            try (PreparedStatement ps = cn.prepareStatement(
                    "SELECT symbol, funding_time, CAST(funding_rate AS DECIMAL(20,10)) "
                  + "FROM funding_rate WHERE funding_time >= ?")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        funding.computeIfAbsent(rs.getString(1), k -> new TreeMap<>())
                                .put(rs.getLong(2), rs.getDouble(3));
                    }
                }
            }

            List<Feature> features = new ArrayList<>();
            for (var e : klines.entrySet()) {
                Feature f = compute(e.getKey(), e.getValue(),
                        oi.getOrDefault(e.getKey(), new TreeMap<>()),
                        lsr.getOrDefault(e.getKey() + "|TOP_POSITION", new TreeMap<>()),
                        lsr.getOrDefault(e.getKey() + "|GLOBAL_ACCOUNT", new TreeMap<>()),
                        funding.getOrDefault(e.getKey(), new TreeMap<>()));
                if (f != null && f.ok()) features.add(f);
            }

            if (only != null) {
                features.stream().filter(f -> f.symbol().equalsIgnoreCase(only))
                        .findFirst().ifPresentOrElse(Stage::printDetail, () -> System.out.println("没有该币的数据: " + only));
                return;
            }

            Map<String, List<Feature>> byStage = new LinkedHashMap<>();
            for (String s : new String[]{"拉升", "试盘", "派发", "砸盘", "吸筹", "冷却"})
                byStage.put(s, new ArrayList<>());
            for (Feature f : features) {
                Verdict v = classify(f);
                if (v.score() >= minScore) byStage.get(v.stage()).add(f);
            }

            System.out.println();
            System.out.printf("%-13s %-6s %-5s %8s %8s %7s %8s %8s %8s%n",
                    "币种", "阶段", "分", "24h%", "OI24h%", "量比", "费率%", "距高%", "散户比");
            for (var e : byStage.entrySet()) {
                List<Feature> list = e.getValue();
                list.sort(Comparator.comparingDouble((Feature f) -> -classify(f).score()));
                for (Feature f : list.subList(0, Math.min(top, list.size()))) {
                    Verdict v = classify(f);
                    System.out.printf("%-13s %-6s %-5d %+8.1f %+8.1f %7.2f %8.4f %8.1f %8.2f%n",
                            f.symbol(), v.stage(), v.score(), f.ret24(), f.oiChg24(), f.volRatio(),
                            f.funding() * 100, f.drawdown(), f.topPos());
                }
            }
            long counted = byStage.values().stream().mapToLong(List::size).sum();
            System.out.printf("%n统计: %d 个币有足够数据, %d 个达到 min-score=%d%n", features.size(), counted, minScore);
        }
    }

    // ---------------- 特征计算 ----------------

    static Feature compute(String symbol, List<double[]> bars, TreeMap<Long, Double> oi,
                           TreeMap<Long, Double> topPos, TreeMap<Long, Double> retail,
                           TreeMap<Long, Double> funding) {
        if (bars.size() < 72) return null;
        double[] last = bars.get(bars.size() - 1);
        long now = (long) last[0];
        double close = last[1];

        double close24 = priceAt(bars, now - DAY);
        double close7d = priceAt(bars, now - 7 * DAY);
        if (close24 <= 0 || close7d <= 0) return null;

        double ret24 = (close - close24) / close24 * 100;
        double ret7d = (close - close7d) / close7d * 100;

        // 24h 成交额 与 前 6 天日均
        double vol24 = 0, volPrev = 0;
        int prevDays = 0;
        double hi24 = 0, lo24 = Double.MAX_VALUE, hi14 = 0;
        for (double[] b : bars) {
            long t = (long) b[0];
            if (t > now - DAY) {
                vol24 += b[4];
                hi24 = Math.max(hi24, b[2]);
                lo24 = Math.min(lo24, b[3]);
            } else if (t > now - 7 * DAY) {
                volPrev += b[4];
            }
            if (t > now - 14 * DAY) hi14 = Math.max(hi14, b[2]);
        }
        prevDays = 6;
        double volPrevAvg = volPrev / prevDays;
        double volRatio = volPrevAvg > 0 ? vol24 / volPrevAvg : 1;
        double range24 = lo24 > 0 && lo24 != Double.MAX_VALUE ? (hi24 - lo24) / lo24 * 100 : 0;
        double drawdown = hi14 > 0 ? (close - hi14) / hi14 * 100 : 0;

        // OI 变化（按币数）
        double oiNow = oiFloor(oi, now);
        double oi24 = oiFloor(oi, now - DAY);
        double oi7d = oiFloor(oi, now - 7 * DAY);
        double oiChg24 = oi24 > 0 ? (oiNow - oi24) / oi24 * 100 : 0;
        double oiChg7d = oi7d > 0 ? (oiNow - oi7d) / oi7d * 100 : 0;
        double oiPeak = 0;
        for (var en : oi.entrySet()) if (en.getKey() > now - 14 * DAY) oiPeak = Math.max(oiPeak, en.getValue());
        double oiFromPeak = oiPeak > 0 && oiNow > 0 ? (oiNow - oiPeak) / oiPeak * 100 : 0;

        double topPosNow = floorVal(topPos, now);
        double retailNow = floorVal(retail, now);
        double retail72 = floorVal(retail, now - 3 * DAY);
        double retailDelta = (retailNow > 0 && retail72 > 0) ? retailNow - retail72 : 0;

        // 资金费率：最新一次 + 近 3 天均值
        double fNow = 0, fSum = 0;
        int fCnt = 0;
        for (var en : funding.entrySet()) {
            if (en.getKey() <= now) fNow = en.getValue();
            if (en.getKey() > now - 3 * DAY) { fSum += en.getValue(); fCnt++; }
        }
        double f3d = fCnt > 0 ? fSum / fCnt : 0;

        return new Feature(symbol, close, ret24, ret7d, volRatio, oiChg24, oiChg7d, oiFromPeak,
                drawdown, range24, fNow, f3d, retailDelta, topPosNow, bars.size());
    }

    private static double priceAt(List<double[]> bars, long t) {
        double best = 0;
        long bestDiff = Long.MAX_VALUE;
        for (double[] b : bars) {
            long d = Math.abs((long) b[0] - t);
            if (d < bestDiff) { bestDiff = d; best = b[1]; }
        }
        return bestDiff <= 2 * HOUR ? best : 0;
    }

    private static double oiFloor(TreeMap<Long, Double> m, long t) {
        if (m.isEmpty()) return 0;
        for (long k = t; k >= t - 6 * HOUR; k -= HOUR) {
            Double v = m.get(k);
            if (v != null) return v;
        }
        return 0;
    }

    private static double floorVal(TreeMap<Long, Double> m, long t) {
        if (m.isEmpty()) return 0;
        for (long k = t; k >= t - 6 * HOUR; k -= HOUR) {
            Double v = m.get(k);
            if (v != null) return v;
        }
        return 0;
    }

    // ---------------- 阶段判定 ----------------

    static Verdict classify(Feature f) {
        List<String> ev = new ArrayList<>();
        int markup = 0, test = 0, dist = 0, dump = 0, accum = 0;

        if (f.ret24() > 10 && f.drawdown() > -25) { markup += 30; ev.add(String.format("24h 涨 %.1f%%", f.ret24())); }
        if (f.oiChg24() > 5) markup += 30;
        if (f.volRatio() > 1.5) markup += 25;
        if (f.funding() > 0.0001) markup += 15;
        // 距 14 天高点仍很深 = 崩过之后的反弹，不是拉升
        if (f.drawdown() < -40) markup -= 30;

        if (f.range24() > 12 && Math.abs(f.ret24()) < 6) { test += 30; }
        if (f.volRatio() > 2) test += 25;
        if (Math.abs(f.ret24()) < 6) test += 20;
        if (Math.abs(f.oiChg24()) > 5) test += 25;

        if (f.drawdown() > -8) dist += 30;
        if (f.oiFromPeak() < -5) { dist += 25; ev.add(String.format("OI 距峰 %.1f%%", f.oiFromPeak())); }
        if (f.volRatio() < 0.6) { dist += 25; ev.add(String.format("量比 %.2f", f.volRatio())); }
        if (f.funding() > 0.0002) dist += 20;

        if (f.ret24() < -12) { dump += 40; ev.add(String.format("24h 跌 %.1f%%", f.ret24())); }
        if (f.oiChg24() < -4) dump += 30;
        if (f.drawdown() < -20) dump += 30;

        if (Math.abs(f.ret7d()) < 8) accum += 20;
        if (f.volRatio() < 0.8) accum += 25;
        if (f.oiChg7d() > 0) accum += 20;
        if (f.funding() <= 0.0001) accum += 15;
        if (f.drawdown() < -20) accum += 20;

        int best = 0;
        String stage = "冷却";
        // 有序比较：并列时保留先出现的阶段（按"事件强度"排序）
        String[] names = {"砸盘", "拉升", "派发", "试盘", "吸筹"};
        int[] scores = {dump, markup, dist, test, accum};
        for (int i = 0; i < names.length; i++) {
            if (scores[i] > best) { best = scores[i]; stage = names[i]; }
        }
        if (best < 40) { stage = "冷却"; best = Math.max(best, 30); }
        return new Verdict(stage, Math.min(100, best), ev);
    }

    static void printDetail(Feature f) {
        Verdict v = classify(f);
        System.out.println();
        System.out.println("币种: " + f.symbol());
        System.out.printf("现价 %.6f   24h %+.1f%%   7d %+.1f%%%n", f.close(), f.ret24(), f.ret7d());
        System.out.printf("量比 %.2f   24h 振幅 %.1f%%   距 14 天高点 %+.1f%%%n", f.volRatio(), f.range24(), f.drawdown());
        System.out.printf("OI 24h %+.1f%%   7d %+.1f%%   距峰 %+.1f%%%n", f.oiChg24(), f.oiChg7d(), f.oiFromPeak());
        System.out.printf("大户持仓比 %.3f   散户比 72h 变化 %+.3f%n", f.topPos(), f.retailDelta());
        System.out.println();
        System.out.printf("判定: %s (置信分 %d)%n", v.stage(), v.score());
        v.evidence().forEach(e -> System.out.println("  · " + e));
    }

    static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }
}
