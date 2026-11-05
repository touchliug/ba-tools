import java.sql.*;
import java.time.*;

public class WifOi {
    public static void main(String[] a) throws Exception {
        String pass = a.length > 0 ? a[0] : System.getenv("DB_PASSWORD");
        String u = "jdbc:mysql://" + System.getenv().getOrDefault("DB_HOST", "127.0.0.1") + ":3306/ba?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try (Connection c = DriverManager.getConnection(u, "root", pass)) {
            System.out.println("=== WIFUSDT 最新 OI 明细 ===");
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT `timestamp`, sum_open_interest, sum_open_interest_value, open_interest "
                  + "FROM open_interest WHERE symbol='WIFUSDT' AND period='1h' ORDER BY `timestamp` DESC LIMIT 3")) {
                while (r.next()) {
                    System.out.printf("%s  币数=%s  名义USD=%s  open_interest列=%s%n",
                            Instant.ofEpochMilli(r.getLong(1)).atZone(ZoneOffset.UTC).toLocalDateTime(),
                            r.getString(2), r.getString(3), r.getString(4));
                }
            }
            System.out.println("\n=== 7 天 OI 区间（币数 / 名义） ===");
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT MIN(CAST(sum_open_interest AS DECIMAL(30,0))), MAX(CAST(sum_open_interest AS DECIMAL(30,0))), "
                  + "MIN(CAST(sum_open_interest_value AS DECIMAL(30,2))), MAX(CAST(sum_open_interest_value AS DECIMAL(30,2))), COUNT(*) "
                  + "FROM open_interest WHERE symbol='WIFUSDT' AND period='1h' "
                  + "AND `timestamp` > UNIX_TIMESTAMP(NOW()-INTERVAL 7 DAY)*1000")) {
                r.next();
                System.out.printf("币数 %.1fM ~ %.1fM   名义 %.1fM ~ %.1fM USD   样本 %d 条%n",
                        r.getDouble(1) / 1e6, r.getDouble(2) / 1e6, r.getDouble(3) / 1e6, r.getDouble(4) / 1e6, r.getInt(5));
            }
            System.out.println("\n=== 对比：24h 成交额（名义，USDT） ===");
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT SUM(CAST(quote_asset_volume AS DECIMAL(30,2)))/1e6 FROM klines "
                  + "WHERE symbol='WIFUSDT' AND `interval`='1h' "
                  + "AND open_time > UNIX_TIMESTAMP(NOW()-INTERVAL 24 HOUR)*1000")) {
                r.next();
                System.out.printf("24h 成交额 = %.1fM USD%n", r.getDouble(1));
            }
        }
    }
}
