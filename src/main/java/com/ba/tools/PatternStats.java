package com.ba.tools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

/** 连跌 N 天后的次日/隔日上涨概率统计（含日期聚类 bootstrap、月度稳定性、跌幅分位）。 */
public class PatternStats {

    static final long DAY = 86_400_000L;
    static final String URL = "jdbc:mysql://43.155.216.33:3306/ba?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&connectTimeout=20000&socketTimeout=300000";
    static final String USER = "root";
    static final StringBuilder OUT = new StringBuilder();
    static void p(String s) { System.out.println(s); OUT.append(s).append('\n'); }
    static void pf(String f, Object... a) { p(String.format(Locale.ROOT, f, a)); }

    record Bar(long t, double c) {}

    static class Ev {
        String sym; int runLen; long t5; String day; double drop5;
        boolean has6, up6, has7, up7; double ret6, ret7; boolean censored;
    }

    static class S {
        int n, up, n7, up7, jn, ju;
        void add(Ev e) {
            if (!e.has6) return;
            n++; if (e.up6) up++;
            if (e.up6 && e.has7) { n7++; if (e.up7) up7++; }
            if (e.has7) { jn++; if (e.up6 && e.up7) ju++; }
        }
        double p6() { return n == 0 ? Double.NaN : up / (double) n; }
        double p7() { return n7 == 0 ? Double.NaN : up7 / (double) n7; }
        double pj() { return jn == 0 ? Double.NaN : ju / (double) jn; }
    }

    static void run(String outDir) throws Exception {
        String pass = Db.pass();
        long now = System.currentTimeMillis();

        Map<String, List<List<Bar>>> segs = new LinkedHashMap<>();
        try (Connection cn = DriverManager.getConnection(URL, USER, pass);
             Statement st = cn.createStatement();
             ResultSet rs = st.executeQuery("SELECT symbol, open_time, CAST(`close` AS DECIMAL(30,10)) c "
                     + "FROM klines WHERE `interval`='1d' ORDER BY symbol, open_time")) {
            Map<String, List<Bar>> tmp = new LinkedHashMap<>();
            while (rs.next()) {
                long t = rs.getLong("open_time");
                if (t + DAY - 1 > now) continue;
                double c = rs.getDouble("c");
                if (c <= 0) continue;
                tmp.computeIfAbsent(rs.getString("symbol"), k -> new ArrayList<>(400)).add(new Bar(t, c));
            }
            for (var e : tmp.entrySet()) {
                List<List<Bar>> ss = new ArrayList<>();
                List<Bar> cur = new ArrayList<>();
                Bar prev = null;
                for (Bar b : e.getValue()) {
                    if (prev != null && b.t - prev.t != DAY) { if (!cur.isEmpty()) ss.add(cur); cur = new ArrayList<>(); }
                    cur.add(b); prev = b;
                }
                if (!cur.isEmpty()) ss.add(cur);
                segs.put(e.getKey(), ss);
            }
        }

        List<Ev> all = new ArrayList<>();
        int upAll = 0, nAll = 0;
        for (var e : segs.entrySet()) {
            for (List<Bar> seg : e.getValue()) {
                int n = seg.size();
                if (n < 2) continue;
                int[] dir = new int[n];
                for (int i = 1; i < n; i++) {
                    double d = seg.get(i).c() - seg.get(i - 1).c();
                    dir[i] = d > 0 ? 1 : d < 0 ? -1 : 0;
                    nAll++; if (dir[i] == 1) upAll++;
                }
                for (int i = 1; i < n; ) {
                    if (dir[i] != -1) { i++; continue; }
                    int start = i;
                    while (i < n && dir[i] == -1) i++;
                    int L = (i - 1) - start + 1;
                    if (L < 5) continue;
                    int ev5 = start + 4;
                    Ev ev = new Ev();
                    ev.sym = e.getKey(); ev.runLen = L; ev.t5 = seg.get(ev5).t();
                    ev.day = utcDay(ev.t5);
                    double c0 = seg.get(ev5 - 5).c();
                    ev.drop5 = (seg.get(ev5).c() - c0) / c0 * 100.0;
                    ev.censored = (start == 1);            // run 从数据集第一根就开始 → 真实长度未知
                    if (ev5 + 1 < n) { ev.has6 = true; ev.up6 = dir[ev5 + 1] == 1; ev.ret6 = pct(seg, ev5 + 1); }
                    if (ev5 + 2 < n) { ev.has7 = true; ev.up7 = dir[ev5 + 2] == 1; ev.ret7 = pct(seg, ev5 + 2); }
                    all.add(ev);
                }
            }
        }
        double base = upAll / (double) nAll;

        List<Ev> clean = new ArrayList<>();
        for (Ev e : all) if (!e.censored) clean.add(e);

        p("=== 数据 ===");
        pf("symbols=%d  基线 P(任意一天上涨)=%.4f (n=%d)", segs.size(), base, nAll);
        pf("事件总数=%d (其中左侧截断 %d 个)", all.size(), all.size() - clean.size());

        S s = new S(); for (Ev e : clean) s.add(e);
        p("");
        p("=== 主结果 (已剔除左侧截断) ===");
        pf("P(第6天上涨)                = %.4f   (%d/%d)  基线 %.4f  差 %+.2f pp", s.p6(), s.up, s.n, base, (s.p6() - base) * 100);
        pf("P(第7天上涨 | 第6天上涨)     = %.4f   (%d/%d)", s.p7(), s.up7, s.n7);
        pf("P(第6、7天都上涨)            = %.4f   (%d/%d)", s.pj(), s.ju, s.jn);
        int n7all = 0, up7all = 0;
        for (Ev e : clean) if (e.has7) { n7all++; if (e.up7) up7all++; }
        pf("P(第7天上涨) 无条件          = %.4f   (%d/%d)", up7all / (double) n7all, up7all, n7all);

        // run 长度分布
        p("");
        p("=== 下跌 run 长度分布 (仅 L>=5) ===");
        Map<Integer, Integer> lh = new TreeMap<>();
        for (Ev e : clean) lh.merge(e.runLen, 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        for (var en : lh.entrySet()) sb.append(String.format(Locale.ROOT, "L=%d:%d  ", en.getKey(), en.getValue()));
        p(sb.toString());
        pf("→ 达到 5 连跌后, 停在第 5 天的比例 = %d/%d = %.4f", lh.getOrDefault(5, 0), clean.size(),
                lh.getOrDefault(5, 0) / (double) clean.size());

        // 日期聚类
        Map<String, List<Ev>> byDay = new TreeMap<>();
        for (Ev e : clean) byDay.computeIfAbsent(e.day, k -> new ArrayList<>()).add(e);
        p("");
        pf("=== 事件聚集度 ===  不同日期数=%d, 平均每日期 %.1f 个事件", byDay.size(), clean.size() / (double) byDay.size());
        byDay.entrySet().stream().sorted((x, y) -> y.getValue().size() - x.getValue().size()).limit(6)
                .forEach(x -> pf("  %s : %d 个事件", x.getKey(), x.getValue().size()));

        // 按日期聚类 bootstrap
        List<List<Ev>> cl = new ArrayList<>(byDay.values());
        Random rnd = new Random(7);
        int B = 3000;
        double[] b6 = new double[B], b7 = new double[B], bj = new double[B];
        for (int b = 0; b < B; b++) {
            S t = new S();
            for (int k = 0; k < cl.size(); k++) for (Ev e : cl.get(rnd.nextInt(cl.size()))) t.add(e);
            b6[b] = t.p6(); b7[b] = t.p7(); bj[b] = t.pj();
        }
        pf("95%%CI (按日期聚类bootstrap, %d 次): 第6天 [%.4f, %.4f]  第7天|涨 [%.4f, %.4f]  联合 [%.4f, %.4f]",
                B, q(b6, .025), q(b6, .975), q(b7, .025), q(b7, .975), q(bj, .025), q(bj, .975));
        pf("   有效独立样本约 = 日期数 %d (远小于事件数 %d)", byDay.size(), clean.size());

        // 月度
        p("");
        p("=== 按月稳定性 ===");
        p("月份      n(事件)  日期数  P6      P7|6    联合");
        Map<String, List<Ev>> byMonth = new TreeMap<>();
        for (Ev e : clean) byMonth.computeIfAbsent(e.day.substring(0, 7), k -> new ArrayList<>()).add(e);
        for (var en : byMonth.entrySet()) {
            S t = new S();
            Set<String> ds = new HashSet<>();
            for (Ev e : en.getValue()) { t.add(e); ds.add(e.day); }
            pf("%s  %-8d %-6d  %.4f  %.4f  %.4f", en.getKey(), t.n, ds.size(), t.p6(), t.p7(), t.pj());
        }

        // 跌幅分位(标签修正)
        p("");
        p("=== 按 5 天累计跌幅分位 (Q1=跌最深) ===");
        List<Ev> sorted = new ArrayList<>(clean);
        sorted.sort(Comparator.comparingDouble(x -> x.drop5));
        int q4 = sorted.size() / 4;
        for (int i = 0; i < 4; i++) {
            int from = i * q4, to = i == 3 ? sorted.size() : (i + 1) * q4;
            S t = new S();
            for (int k = from; k < to; k++) t.add(sorted.get(k));
            pf("  Q%d (%+6.1f%% .. %+6.1f%%) n=%-4d P6=%.4f P7|6=%.4f",
                    i + 1, sorted.get(from).drop5, sorted.get(to - 1).drop5, t.n, t.p6(), t.p7());
        }

        // run 长度 × P6 的一致性检验
        p("");
        p("=== 若真无边缘效应(独立同分布), 预期 P6 ≈ 基线; 实测差异 ===");
        double se = Math.sqrt(s.p6() * (1 - s.p6()) / byDay.size()) * Math.sqrt(1.0 * clean.size() / byDay.size());
        pf("  P6=%.4f vs 基线 %.4f, 差 %+.2f pp (按日期聚类后近似 SE≈%.4f, z≈%.1f)",
                s.p6(), base, (s.p6() - base) * 100, se, (s.p6() - base) / se);

        try (Writer w = new OutputStreamWriter(new FileOutputStream(outDir + "/pattern_report.txt"), StandardCharsets.UTF_8)) {
            w.write(OUT.toString());
        }
        p("");
        p("报告已写出 " + outDir + "/pattern_report.txt");
    }

    static double pct(List<Bar> seg, int i) {
        double x = seg.get(i - 1).c(), y = seg.get(i).c();
        return x == 0 ? 0 : (y - x) / x * 100.0;
    }
    static double q(double[] arr, double q) {
        double[] c = arr.clone(); Arrays.sort(c);
        return c[Math.max(0, Math.min(c.length - 1, (int) Math.round(q * (c.length - 1))))];
    }
    static String utcDay(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }
}
