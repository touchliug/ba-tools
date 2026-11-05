import java.sql.*;
import java.time.*;

public class WifDetail {
    public static void main(String[] a) throws Exception {
        String pass = a.length > 0 ? a[0] : System.getenv("DB_PASSWORD");
        String u = "jdbc:mysql://" + System.getenv().getOrDefault("DB_HOST", "127.0.0.1") + ":3306/ba?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        String S = "WIFUSDT";
        try (Connection c = DriverManager.getConnection(u, "root", pass)) {

            System.out.println("=== 日线覆盖 ===");
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT COUNT(*), MIN(open_time), MAX(open_time), MIN(CAST(low AS DECIMAL(30,10))), "
                  + "MAX(CAST(high AS DECIMAL(30,10))) FROM klines WHERE symbol=? AND `interval`='1d'")) {
                p.setString(1, S);
                try (ResultSet r = p.executeQuery()) {
                    r.next();
                    System.out.printf("days=%d  %s ~ %s  历史低=%.6f 历史高=%.6f%n", r.getInt(1),
                            Instant.ofEpochMilli(r.getLong(2)).atZone(ZoneOffset.UTC).toLocalDate(),
                            Instant.ofEpochMilli(r.getLong(3)).atZone(ZoneOffset.UTC).toLocalDate(),
                            r.getDouble(4), r.getDouble(5));
                }
            }

            System.out.println("\n=== 近期日线（14 天） ===");
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT open_time, CAST(`close` AS DECIMAL(30,10)), CAST(high AS DECIMAL(30,10)), "
                  + "CAST(low AS DECIMAL(30,10)), ROUND(CAST(quote_asset_volume AS DECIMAL(30,2))/1e6,1) "
                  + "FROM klines WHERE symbol=? AND `interval`='1d' ORDER BY open_time DESC LIMIT 14")) {
                p.setString(1, S);
                try (ResultSet r = p.executeQuery()) {
                    System.out.printf("%-12s %9s %9s %9s %8s%n", "date", "close", "high", "low", "volM");
                    while (r.next()) {
                        System.out.printf("%-12s %9.5f %9.5f %9.5f %8.1f%n",
                                Instant.ofEpochMilli(r.getLong(1)).atZone(ZoneOffset.UTC).toLocalDate(),
                                r.getDouble(2), r.getDouble(3), r.getDouble(4), r.getDouble(5));
                    }
                }
            }

            System.out.println("\n=== 90 天区间分位 ===");
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT MIN(CAST(low AS DECIMAL(30,10))), MAX(CAST(high AS DECIMAL(30,10))) FROM klines "
                  + "WHERE symbol=? AND `interval`='1d' AND open_time > UNIX_TIMESTAMP(NOW()-INTERVAL 90 DAY)*1000")) {
                p.setString(1, S);
                try (ResultSet r = p.executeQuery()) {
                    r.next();
                    double lo = r.getDouble(1), hi = r.getDouble(2);
                    double now = 0.1766;
                    System.out.printf("90d 低=%.5f 高=%.5f  现价分位=%.0f%%%n", lo, hi, (now - lo) / (hi - lo) * 100);
                }
            }

            System.out.println("\n=== 多空比（4 类最新） ===");
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT l.ratio_type, l.long_short_ratio, l.long_value, l.short_value, l.`timestamp` "
                  + "FROM long_short_ratio l JOIN (SELECT ratio_type, MAX(`timestamp`) mt FROM long_short_ratio "
                  + "WHERE symbol=? AND period='1h' GROUP BY ratio_type) g "
                  + "ON l.ratio_type=g.ratio_type AND l.`timestamp`=g.mt WHERE l.symbol=? AND l.period='1h'")) {
                p.setString(1, S); p.setString(2, S);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        System.out.printf("%-14s 比值=%.3f  多侧=%.4f  空侧=%.4f%n",
                                r.getString(1), r.getDouble(2), r.getDouble(3), r.getDouble(4));
                    }
                }
            }

            System.out.println("\n=== 资金费率（最近 12 次 / 7 天均值） ===");
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT funding_time, CAST(funding_rate AS DECIMAL(20,10)) FROM funding_rate "
                  + "WHERE symbol=? ORDER BY funding_time DESC LIMIT 12")) {
                p.setString(1, S);
                try (ResultSet r = p.executeQuery()) {
                    StringBuilder sb = new StringBuilder();
                    while (r.next()) {
                        sb.append(String.format("%.4f%% ", r.getDouble(2) * 100));
                    }
                    System.out.println("最近 12 次: " + sb);
                }
            }
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT AVG(CAST(funding_rate AS DECIMAL(20,10))), MIN(CAST(funding_rate AS DECIMAL(20,10))), "
                  + "MAX(CAST(funding_rate AS DECIMAL(20,10))) FROM funding_rate "
                  + "WHERE symbol=? AND funding_time > UNIX_TIMESTAMP(NOW()-INTERVAL 7 DAY)*1000")) {
                p.setString(1, S);
                try (ResultSet r = p.executeQuery()) {
                    r.next();
                    System.out.printf("7 天: 均值 %.4f%%  最低 %.4f%%  最高 %.4f%%（每次 8 小时）%n",
                            r.getDouble(1) * 100, r.getDouble(2) * 100, r.getDouble(3) * 100);
                }
            }

            System.out.println("\n=== OI 币数（最近 7 天，每 12 小时一个点） ===");
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT `timestamp`, CAST(sum_open_interest AS DECIMAL(30,0)) FROM open_interest "
                  + "WHERE symbol=? AND period='1h' ORDER BY `timestamp` DESC LIMIT 168")) {
                p.setString(1, S);
                try (ResultSet r = p.executeQuery()) {
                    int i = 0;
                    while (r.next()) {
                        if (i++ % 24 != 0) continue;
                        System.out.printf("%s  %.1fM 枚%n",
                                Instant.ofEpochMilli(r.getLong(1)).atZone(ZoneOffset.UTC).toLocalDateTime(),
                                r.getDouble(2) / 1e6);
                    }
                }
            }
        }
    }
}
