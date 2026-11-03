package com.ba.tools;

import java.util.Arrays;

/**
 * ba-tools 命令分发入口。
 *
 * <pre>
 *   java -jar ba-tools.jar backfill    补 K 线历史（币安 → MySQL）
 *   java -jar ba-tools.jar pattern     连跌 N 天后的上涨概率统计
 *   java -jar ba-tools.jar ain-review  单币逐小时复盘（插针/挤空/双向扫）
 *   java -jar ba-tools.jar db-probe    数据库体检（规模/索引/查询耗时）
 * </pre>
 */
public final class Main {

    private Main() {}

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0 || "help".equals(argv[0]) || "--help".equals(argv[0]) || "-h".equals(argv[0])) {
            usage();
            return;
        }
        String cmd = argv[0];
        String[] rest = Arrays.copyOfRange(argv, 1, argv.length);
        Args a = new Args(rest);

        switch (cmd) {
            case "backfill" -> Backfill.run(rest);
            case "pattern" -> PatternStats.run(a.get("out", "."));
            case "ain-review" -> AinReview.run(
                    a.get("symbol", "AINUSDT"),
                    a.getInt("hours", 120),
                    a.get("out", "."));
            case "db-probe" -> DbProbe.run(a);
            default -> {
                System.err.println("未知命令: " + cmd);
                usage();
                System.exit(1);
            }
        }
    }

    private static void usage() {
        System.out.println("""
                ba-tools — 币安合约数据小工具集

                用法: java -jar ba-tools.jar <命令> [选项]

                命令:
                  backfill     从币安补 K 线历史到 MySQL（幂等 upsert）
                    --years 3 --interval 1d --proxy 127.0.0.1:7897
                    --symbols BTCUSDT,ETHUSDT  --max-symbols N
                    --check（只体检不联网）  --selftest（只测写库）
                    --dry-run（拉取不写库）  --rps 3
                    --db-host/--db-port/--db-name/--db-user/--db-pass

                  pattern      连跌 5 天后第 6/7 天上涨概率（含聚类 bootstrap）
                    --out 输出目录（默认当前目录）

                  ain-review   单币逐小时复盘，识别插针/挤空/双向扫
                    --symbol AINUSDT   --hours 120   --out 输出目录

                  db-probe     数据库体检：表规模、索引、典型查询耗时
                    --symbol BTCUSDT   --interval 1h   --limit 72

                密码: --db-pass，或环境变量 DB_PASSWORD
                """);
    }
}
