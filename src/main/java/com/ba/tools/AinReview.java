package com.ba.tools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AINUSDT 逐小时复盘: 识别「诱空→爆空」「挤空式拉升」「双向扫」三类插针事件。
 * 输出: 控制台 + ain_review.md
 */
public class AinReview {
    static final String URL = "jdbc:mysql://43.155.216.33:3306/ba?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&connectTimeout=20000&socketTimeout=300000";
    static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    record Bar(long t, double o, double h, double l, double c, double vol, double oi, double topPos) {}

    /** @param symbol 交易对; @param hours 回看小时数; @param outDir 报告输出目录 */
    static void run(String symbol, int hours, String outDir) throws Exception {
        long start = System.currentTimeMillis() - (long) hours * 3_600_000L;
        List<Bar> bars = new ArrayList<>();
        try (Connection cn = DriverManager.getConnection(URL, "root", Db.pass())) {
            String sql = "SELECT k.open_time t, CAST(k.open AS DECIMAL(30,10)) o, CAST(k.high AS DECIMAL(30,10)) h, "
                    + "CAST(k.low AS DECIMAL(30,10)) l, CAST(k.`close` AS DECIMAL(30,10)) c, "
                    + "CAST(k.quote_asset_volume AS DECIMAL(30,2)) v, "
                    + "CAST(o.sum_open_interest AS DECIMAL(30,2)) oi, CAST(t.long_short_ratio AS DECIMAL(20,6)) tp "
                    + "FROM klines k "
                    + "LEFT JOIN open_interest o ON o.symbol=k.symbol AND o.period='1h' AND o.`timestamp`=k.open_time "
                    + "LEFT JOIN long_short_ratio t ON t.symbol=k.symbol AND t.period='1h' AND t.ratio_type='TOP_POSITION' AND t.`timestamp`=k.open_time "
                    + "WHERE k.symbol=? AND k.`interval`='1h' AND k.open_time >= ? ORDER BY k.open_time";
            try (PreparedStatement ps = cn.prepareStatement(sql)) {
                ps.setString(1, symbol); ps.setLong(2, start);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Object oio = rs.getObject("oi");
                        Object otp = rs.getObject("tp");
                        bars.add(new Bar(rs.getLong("t"), rs.getDouble("o"), rs.getDouble("h"), rs.getDouble("l"),
                                rs.getDouble("c"), rs.getDouble("v"),
                                oio == null ? Double.NaN : ((Number) oio).doubleValue(),
                                otp == null ? Double.NaN : ((Number) otp).doubleValue()));
                    }
                }
            }
        }
        System.out.printf("bars=%d%n", bars.size());

        StringBuilder md = new StringBuilder();
        md.append("# ").append(symbol).append(" 逐小时复盘（最近 ").append(hours).append(" 小时）\n\n");
        md.append("> 数据源：klines(1h) + open_interest(1h, 币数) + long_short_ratio(TOP_POSITION)\n\n");

        // 逐小时指标
        List<String> lines = new ArrayList<>();
        lines.add("| 时间(UTC) | 收盘 | 小时涨跌 | 振幅 | 成交额M | OI变化 | 大户比 | 标记 |");
        lines.add("|---|---|---|---|---|---|---|---|");

        // 事件识别 + 合并为 episode
        List<int[]> episodes = new ArrayList<>();   // [startIdx, endIdx]
        int i = 0;
        while (i < bars.size()) {
            if (!isEvent(bars, i)) { i++; continue; }
            int s = i;
            while (i < bars.size() && isEvent(bars, i)) i++;
            episodes.add(new int[]{s, i - 1});
        }

        int nShake = 0, nSqueeze = 0, nBoth = 0;
        double sum24 = 0; int cnt24 = 0;

        for (int[] ep : episodes) {
            int s = ep[0], e = ep[1];
            Bar first = bars.get(s), last = bars.get(e);
            double p0 = s > 0 ? bars.get(s - 1).c() : first.o;
            double priceMove = (last.c - p0) / p0 * 100;
            double oiBase = (s > 0) ? bars.get(s - 1).oi : Double.NaN;
            double oiMove = (Double.isNaN(oiBase) || Double.isNaN(last.oi) || oiBase <= 0) ? Double.NaN
                    : (last.oi - oiBase) / oiBase * 100;
            double volSum = 0, hi = 0, lo = Double.MAX_VALUE;
            for (int k = s; k <= e; k++) { volSum += bars.get(k).vol; hi = Math.max(hi, bars.get(k).h); lo = Math.min(lo, bars.get(k).l); }
            double range = (hi - lo) / lo * 100;

            String tag;
            if (priceMove > 5 && !Double.isNaN(oiMove) && oiMove < -2) { tag = "挤空拉升(空头爆仓)"; nSqueeze++; }
            else if (priceMove < -4 && !Double.isNaN(oiMove) && oiMove > 2) { tag = "砸盘诱空(空头加仓)"; nShake++; }
            else if (range > 15) { tag = "双向扫"; nBoth++; }
            else if (priceMove < -4) { tag = "多头止损/平仓"; }
            else if (priceMove > 5) { tag = "拉升"; }
            else { tag = "异动"; }

            String fwd = "--";
            if (e + 24 < bars.size()) {
                double f = (bars.get(e + 24).c - last.c) / last.c * 100;
                sum24 += f; cnt24++;
                fwd = String.format("%+.1f%%", f);
            }

            String tp = Double.isNaN(last.topPos) ? "--" : String.format("%.3f", last.topPos);
            lines.add(String.format("| %s | %.5f | %+.1f%% | %.1f%% | %.1f | %s | %s | **%s** → 24h %s |",
                    Instant.ofEpochMilli(first.t).atZone(ZoneOffset.UTC).format(DF), last.c,
                    priceMove, range, volSum / 1e6,
                    Double.isNaN(oiMove) ? "--" : String.format("%+.1f%%", oiMove),
                    tp, tag, fwd));
        }

        md.append(String.join("\n", lines)).append("\n\n");

        double maxRange = 0; String maxRangeAt = "";
        for (int k = 1; k < bars.size(); k++) {
            double r = (bars.get(k).h - bars.get(k).l) / bars.get(k).l * 100;
            if (r > maxRange) { maxRange = r; maxRangeAt = Instant.ofEpochMilli(bars.get(k).t).atZone(ZoneOffset.UTC).format(DF); }
        }

        md.append("## 汇总\n\n");
        md.append("- 事件段数: ").append(episodes.size()).append("\n");
        md.append("- 双向扫: ").append(nBoth).append(" 次；砸盘诱空: ").append(nShake).append(" 次；挤空拉升: ").append(nSqueeze).append(" 次\n");
        md.append(String.format("- 事件后 24h 平均收益: %+.2f%%（%d 个样本）%n", sum24 / Math.max(1, cnt24), cnt24));
        md.append(String.format("- 最大单小时振幅: %.1f%%（%s）%n", maxRange, maxRangeAt));
        Bar lastBar = bars.get(bars.size() - 1);
        md.append(String.format("- 最新: 收盘 %.5f, OI %s, 大户持仓比 %s%n",
                lastBar.c,
                Double.isNaN(lastBar.oi) ? "未同步" : String.format("%.1fM 枚", lastBar.oi / 1e6),
                Double.isNaN(lastBar.topPos) ? "未同步" : String.format("%.3f", lastBar.topPos)));

        try (Writer w = new OutputStreamWriter(new FileOutputStream(outDir + "/ain_review_" + symbol + ".md"), StandardCharsets.UTF_8)) {
            w.write(md.toString());
        }
        System.out.println(md);
    }

    static boolean isEvent(List<Bar> bars, int i) {
        Bar b = bars.get(i);
        Bar p = i > 0 ? bars.get(i - 1) : null;
        if (p == null) return false;
        double chg = (b.c - p.c) / p.c * 100;
        double range = (b.h - b.l) / b.l * 100;
        double oiChg = (p.oi != p.oi || b.oi != b.oi || p.oi <= 0) ? 0 : (b.oi - p.oi) / p.oi * 100;
        return Math.abs(chg) >= 5 || range >= 12 || oiChg <= -3;
    }
}
