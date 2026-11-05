package com.ba.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段回测：验证「阶段标签」到底有没有预测力。
 *
 * <p>做法：对历史上每个时点用与实盘相同的规则打一次标签（特征只用该时点之前的数据，
 * 不含未来），然后统计该时点之后 3/7 天的收益分布，并与全样本基线对比。
 *
 * <p>如果没有区分度，说明阶段标签只是描述，不能作为交易依据。
 */
final class Backtest {

    private Backtest() {}

    static void run(Args a) throws Exception {
        int lookbackDays = a.getInt("lookback", 120);
        int horizon = a.getInt("horizon", 7);

        Map<String, List<Stage.Day>> series = Stage.load(a);
        double[] btc = Stage.btcReturns(series);
        System.out.printf("加载 %d 个币，回看 %d 天，前向 %d 天%n%n", series.size(), lookbackDays, horizon);

        Map<String, List<Double>> fwdByStage = new LinkedHashMap<>();
        List<Double> baseline = new ArrayList<>();
        int samples = 0;

        for (var e : series.entrySet()) {
            List<Stage.Day> d = e.getValue();
            int n = d.size();
            int start = Math.max(40, n - lookbackDays);
            int end = n - horizon - 1;
            for (int i = start; i <= end; i++) {
                Stage.Feat f = Stage.featureAt(e.getKey(), d, i, btc);
                if (f == null) continue;
                Stage.Verdict v = Stage.classify(f);
                double c0 = d.get(i).close();
                double c1 = d.get(i + horizon).close();
                if (c0 <= 0) continue;
                double fwd = c1 / c0 - 1;
                if (Double.isNaN(fwd) || Math.abs(fwd) > 3) continue;   // 过滤异常值(拆并币)
                fwdByStage.computeIfAbsent(v.stage(), k -> new ArrayList<>()).add(fwd);
                baseline.add(fwd);
                samples++;
            }
        }

        if (samples == 0) { System.out.println("样本不足"); return; }

        double baseMed = median(baseline);
        double baseWin = winRate(baseline);

        System.out.printf("基线（全部 %d 个样本）：%d 日中位数 %+.2f%%，胜率 %.1f%%%n%n",
                samples, horizon, baseMed * 100, baseWin * 100);

        System.out.printf("%-8s %8s %10s %10s %10s %12s%n",
                "阶段", "样本", horizon + "d中位数", horizon + "d均值", "胜率", "相对基线");
        for (String key : new String[]{"拉升", "试盘", "派发", "砸盘", "吸筹", "冷却"}) {
            List<Double> list = fwdByStage.get(key);
            if (list == null || list.size() < 30) {
                System.out.printf("%-8s %8s%n", key, list == null ? "0" : list.size() + "(样本不足)");
                continue;
            }
            double med = median(list);
            double avg = list.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double win = winRate(list);
            System.out.printf("%-8s %8d %+9.2f%% %+9.2f%% %9.1f%% %+11.2f pp%n",
                    key, list.size(), med * 100, avg * 100, win * 100, (med - baseMed) * 100);
        }

        System.out.println();
        long significant = fwdByStage.values().stream().filter(l -> l.size() >= 30).count();
        System.out.printf("结论：%d 个阶段样本量达标。若「相对基线」列都在 ±1pp 内，说明阶段标签没有预测力，只能当描述用。%n", significant);
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
