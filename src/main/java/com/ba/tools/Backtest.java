package com.ba.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * 阶段回测：验证「阶段标签」到底有没有预测力。
 *
 * <p>做法：对历史上每个时点用与实盘相同的规则打一次标签（特征只用该时点之前的数据），
 * 然后统计之后 N 天的收益分布，并与基线对比。
 *
 * <p>两个关键防坑：
 * <ul>
 *   <li><b>采样步长</b>：默认 = 前向天数，避免相邻样本重叠导致显著性被高估。</li>
 *   <li><b>按日期聚类 bootstrap</b>：同一天几百个币同时触发，彼此高度相关，
 *       按事件数算自由度会严重高估置信度（这是 5 连跌那次踩过的坑）。</li>
 * </ul>
 */
final class Backtest {

    private Backtest() {}

    record Sample(String stage, double fwd) {}

    static void run(Args a) throws Exception {
        int lookbackDays = a.getInt("lookback", 120);
        int horizon = a.getInt("horizon", 7);
        int stride = Math.max(1, a.getInt("stride", horizon));
        int boot = a.getInt("bootstrap", 500);

        Map<String, List<Stage.Day>> series = Stage.load(a, true);
        double[] btc = Stage.btcReturns(series);
        System.out.printf("加载 %d 个币，回看 %d 天，前向 %d 天，步长 %d 天%s%n%n",
                series.size(), lookbackDays, horizon, stride,
                stride < horizon ? "（样本重叠，显著性会被高估）" : "");

        Map<Long, List<Sample>> byDate = new TreeMap<>();
        for (var e : series.entrySet()) {
            List<Stage.Day> d = e.getValue();
            int n = d.size();
            int start = Math.max(40, n - lookbackDays);
            int end = n - horizon - 1;
            for (int i = start; i <= end; i += stride) {
                Stage.Feat f = Stage.featureAt(e.getKey(), d, i, btc);
                if (f == null) continue;
                double c0 = d.get(i).close(), c1 = d.get(i + horizon).close();
                if (c0 <= 0) continue;
                double fwd = c1 / c0 - 1;
                if (Double.isNaN(fwd) || Math.abs(fwd) > 3) continue;   // 过滤拆并币等异常
                Stage.Verdict v = Stage.classify(f);
                byDate.computeIfAbsent(d.get(i).t() / 86_400_000L, k -> new ArrayList<>())
                        .add(new Sample(v.stage(), fwd));
            }
        }

        int total = byDate.values().stream().mapToInt(List::size).sum();
        if (total == 0) { System.out.println("样本不足"); return; }

        List<Double> all = new ArrayList<>();
        byDate.values().forEach(l -> l.forEach(s -> all.add(s.fwd())));
        double baseMed = median(all);
        double baseWin = winRate(all);

        System.out.printf("样本：%d 个，覆盖 %d 个交易日%n", total, byDate.size());
        System.out.printf("基线：%d 日中位数 %+.2f%%，胜率 %.1f%%%n%n", horizon, baseMed * 100, baseWin * 100);

        System.out.printf("%-8s %7s %11s %8s %12s %18s%n",
                "阶段", "样本", horizon + "d中位数", "胜率", "vs 基线", "90%CI（日期聚类）");
        for (String key : new String[]{"拉升", "试盘", "派发", "砸盘", "吸筹", "冷却"}) {
            List<Double> list = new ArrayList<>();
            byDate.values().forEach(l -> l.stream().filter(s -> s.stage().equals(key)).forEach(s -> list.add(s.fwd())));
            if (list.size() < 30) { System.out.printf("%-8s %7s%n", key, list.size() + "(不足)"); continue; }

            double med = median(list);
            double[] ci = clusteredCi(byDate, key, baseMed, boot);
            System.out.printf("%-8s %7d %+10.2f%% %7.1f%% %+11.2f pp   [%+.2f, %+.2f]%n",
                    key, list.size(), med * 100, winRate(list) * 100, (med - baseMed) * 100,
                    ci[0] * 100, ci[1] * 100);
        }

        System.out.println();
        System.out.println("判读：CI 跨 0 → 该阶段与基线没有显著差异。");
        System.out.printf("（bootstrap %d 次，按交易日整群重采样；同日多币不视为独立观测）%n", boot);
    }

    /**
     * 按日期聚类 bootstrap：重采样"交易日"而不是"事件"。
     * 返回该阶段中位数相对基线的差值 5%–95% 区间。
     */
    static double[] clusteredCi(Map<Long, List<Sample>> byDate, String stage, double baseMed, int reps) {
        List<Map.Entry<Long, List<Sample>>> days = new ArrayList<>(byDate.entrySet());
        Random rnd = new Random(42);
        List<Double> diffs = new ArrayList<>(reps);
        for (int b = 0; b < reps; b++) {
            List<Double> pool = new ArrayList<>();
            List<Double> stagePool = new ArrayList<>();
            for (int k = 0; k < days.size(); k++) {
                List<Sample> picked = days.get(rnd.nextInt(days.size())).getValue();
                for (Sample s : picked) {
                    pool.add(s.fwd());
                    if (s.stage().equals(stage)) stagePool.add(s.fwd());
                }
            }
            if (stagePool.size() < 20 || pool.isEmpty()) continue;
            diffs.add(median(stagePool) - median(pool));
        }
        if (diffs.isEmpty()) return new double[]{Double.NaN, Double.NaN};
        Collections.sort(diffs);
        return new double[]{diffs.get((int) (diffs.size() * 0.05)), diffs.get((int) (diffs.size() * 0.95))};
    }

    static double median(List<Double> list) {
        List<Double> s = new ArrayList<>(list);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    static double winRate(List<Double> list) {
        long win = list.stream().filter(v -> v > 0).count();
        return list.isEmpty() ? 0 : win / (double) list.size();
    }
}
