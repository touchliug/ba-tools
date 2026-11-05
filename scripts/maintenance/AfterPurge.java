import java.sql.*;

public class AfterPurge {
    public static void main(String[] a) throws Exception {
        String pass = a.length > 0 ? a[0] : System.getenv("DB_PASSWORD");
        String u = "jdbc:mysql://" + System.getenv().getOrDefault("DB_HOST", "127.0.0.1") + ":3306/ba?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try (Connection c = DriverManager.getConnection(U, System.getenv().getOrDefault("DB_USER", "root"), pass)) {
            System.out.println("=== symbols ===");
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT status, COUNT(*) FROM symbols GROUP BY status")) {
                while (r.next()) System.out.printf("%-10s %d%n", r.getString(1), r.getInt(2));
            }
            System.out.println("\n=== klines 行数 ===");
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT `interval`, COUNT(*), COUNT(DISTINCT symbol) FROM klines GROUP BY `interval`")) {
                while (r.next()) System.out.printf("%-5s %10d 行  %4d 币%n", r.getString(1), r.getLong(2), r.getInt(3));
            }
            System.out.println("\n=== 其他表 ===");
            for (String t : new String[]{"open_interest", "long_short_ratio", "funding_rate", "ticker_24h", "premium_index"}) {
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + t)) {
                    r.next();
                    System.out.printf("%-18s %10d%n", t, r.getLong(1));
                }
            }
        }
    }
}
