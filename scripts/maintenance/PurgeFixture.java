import java.sql.*;

/** 为 RetentionService 的端到端测试准备/清理假数据。 */
public class PurgeFixture {
    static final String U = "jdbc:mysql://" + System.getenv().getOrDefault("DB_HOST", "127.0.0.1") + ":3306/ba?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    static final String S = "ZZPURGETEST";

    public static void main(String[] a) throws Exception {
        String pass = (a.length > 0 && !a[0].startsWith("--")) ? a[0] : System.getenv("DB_PASSWORD");
        String mode = a.length > 1 ? a[1] : "--setup";
        try (Connection c = DriverManager.getConnection(U, System.getenv().getOrDefault("DB_USER", "root"), pass)) {
            if ("--setup".equals(mode)) {
                long eightDaysAgo = System.currentTimeMillis() - 8L * 86_400_000L;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO symbols (symbol, pair, contract_type, status, base_asset, quote_asset, "
                      + "price_precision, quantity_precision, onboard_date, delivery_date, updated_at, delisted_at) "
                      + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status), delisted_at=VALUES(delisted_at)")) {
                    ps.setString(1, S); ps.setString(2, S); ps.setString(3, "PERPETUAL"); ps.setString(4, "DELISTED");
                    ps.setString(5, "ZZT"); ps.setString(6, "USDT");
                    ps.setInt(7, 4); ps.setInt(8, 0);
                    ps.setLong(9, eightDaysAgo); ps.setLong(10, 0);
                    ps.setLong(11, eightDaysAgo); ps.setLong(12, eightDaysAgo);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO klines (symbol, `interval`, open_time, `close`) VALUES (?, '1d', ?, '1.0') "
                      + "ON DUPLICATE KEY UPDATE `close`='1.0'")) {
                    for (int i = 0; i < 3; i++) { ps.setString(1, S); ps.setLong(2, 1_700_000_000_000L + i * 86_400_000L); ps.addBatch(); }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO funding_rate (symbol, funding_time, funding_rate) VALUES (?, ?, '0.0001') "
                      + "ON DUPLICATE KEY UPDATE funding_rate='0.0001'")) {
                    ps.setString(1, S); ps.setLong(2, 1_700_000_000_000L); ps.executeUpdate();
                }
                System.out.println("fixture created: 1 symbol + 3 klines + 1 funding, delisted_at = 8 天前");
            } else {
                for (String t : new String[]{"klines", "funding_rate", "open_interest", "long_short_ratio", "ticker_24h", "premium_index", "symbols"}) {
                    int n = c.createStatement().executeUpdate("DELETE FROM " + t + " WHERE symbol='" + S + "'");
                    if (n > 0) System.out.printf("cleanup %-18s %d%n", t, n);
                }
                int p = c.createStatement().executeUpdate("DELETE FROM purge_log WHERE symbol='" + S + "'");
                if (p > 0) System.out.println("cleanup purge_log        " + p);
            }
        }
    }
}
