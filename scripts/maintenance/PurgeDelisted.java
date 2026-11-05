import java.sql.*;
import java.util.*;

/** 下架币清理：先审计范围（--audit），确认后再执行（--delete）。 */
public class PurgeDelisted {
    public static void main(String[] a) throws Exception {
        String pass = (a.length > 0 && !a[0].startsWith("--")) ? a[0] : System.getenv("DB_PASSWORD");
        boolean doDelete = Arrays.asList(a).contains("--delete");
        String u = "jdbc:mysql://" + System.getenv().getOrDefault("DB_HOST", "127.0.0.1") + ":3306/ba?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

        try (Connection c = DriverManager.getConnection(U, System.getenv().getOrDefault("DB_USER", "root"), pass)) {
            c.setAutoCommit(true);

            List<String> delisted = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT symbol FROM symbols WHERE status <> 'TRADING' ORDER BY symbol")) {
                while (r.next()) delisted.add(r.getString(1));
            }
            System.out.println("下架/非交易状态合约: " + delisted.size() + " 个");
            System.out.println("  " + String.join(", ", delisted));

            if (delisted.isEmpty()) { System.out.println("没有需要清理的币"); return; }

            String in = String.join(",", Collections.nCopies(delisted.size(), "?"));
            String[] tables = {"klines", "open_interest", "long_short_ratio", "funding_rate", "ticker_24h", "premium_index", "symbols"};

            System.out.printf("%n%-20s %10s%n", "表", "待删行数");
            long total = 0;
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String t : tables) {
                String col = "symbols".equals(t) ? "symbol" : "symbol";
                long n = count(c, "SELECT COUNT(*) FROM " + t + " WHERE " + col + " IN (" + in + ")", delisted);
                counts.put(t, n);
                total += n;
                System.out.printf("%-20s %10d%n", t, n);
            }
            System.out.printf("%-20s %10d%n", "合计", total);

            if (!doDelete) {
                System.out.println("\n[审计模式] 未做任何修改。确认无误后加 --delete 执行。");
                return;
            }

            System.out.println("\n开始删除（单事务）…");
            c.setAutoCommit(false);
            try {
                for (String t : tables) {
                    String sql = "DELETE FROM " + t + " WHERE symbol IN (" + in + ")";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        for (int i = 0; i < delisted.size(); i++) ps.setString(i + 1, delisted.get(i));
                        int n = ps.executeUpdate();
                        System.out.printf("  %-20s 删除 %d 行%n", t, n);
                    }
                }
                c.commit();
                System.out.println("已提交。");
            } catch (SQLException e) {
                c.rollback();
                System.out.println("失败，已回滚: " + e.getMessage());
                throw e;
            }
        }
    }

    static long count(Connection c, String sql, List<String> args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) ps.setString(i + 1, args.get(i));
            try (ResultSet r = ps.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
}
