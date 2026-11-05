import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * 下架币清理：--dump 先导出备份，--delete 再删除，--verify 只看现状。
 * 备份是可恢复的 INSERT 语句（gzip），因为下架币的数据从币安已经拉不回来了。
 */
public class DumpAndPurge {
    static final String U = "jdbc:mysql://" + System.getenv().getOrDefault("DB_HOST", "127.0.0.1") + ":3306/ba?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    static final String[] TABLES = {"klines", "open_interest", "long_short_ratio", "funding_rate", "ticker_24h", "premium_index", "symbols"};

    public static void main(String[] a) throws Exception {
        String pass = null;
        String mode = "--verify";
        for (String s : a) {
            if (!s.startsWith("--")) pass = s; else mode = s;
        }
        if (pass == null) pass = System.getenv("DB_PASSWORD");

        try (Connection c = DriverManager.getConnection(U, System.getenv().getOrDefault("DB_USER", "root"), pass)) {
            List<String> syms = delisted(c);
            System.out.println("下架合约: " + syms.size() + " 个");
            if (syms.isEmpty()) return;
            String in = String.join(",", Collections.nCopies(syms.size(), "?"));

            if ("--verify".equals(mode)) {
                for (String t : TABLES) System.out.printf("%-20s %d%n", t, count(c, t, in, syms));
                return;
            }

            if ("--dump".equals(mode)) {
                String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                File dir = new File("D:/deepseekwork/backup");
                dir.mkdirs();
                File out = new File(dir, "delisted-" + ts + ".sql.gz");
                long rows = 0;
                try (Writer w = new BufferedWriter(new OutputStreamWriter(
                        new GZIPOutputStream(new FileOutputStream(out)), StandardCharsets.UTF_8))) {
                    w.write("-- 下架币归档 " + ts + "\n");
                    w.write("-- 币种: " + String.join(",", syms) + "\n\n");
                    for (String t : TABLES) {
                        rows += dumpTable(c, t, in, syms, w);
                    }
                }
                System.out.printf("已导出 %d 行 -> %s (%.1f MB)%n", rows, out.getPath(), out.length() / 1048576.0);
                return;
            }

            if ("--delete".equals(mode)) {
                c.setAutoCommit(false);
                try {
                    long total = 0;
                    for (String t : TABLES) {
                        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + t + " WHERE symbol IN (" + in + ")")) {
                            for (int i = 0; i < syms.size(); i++) ps.setString(i + 1, syms.get(i));
                            int n = ps.executeUpdate();
                            total += n;
                            System.out.printf("  %-20s 删除 %d 行%n", t, n);
                        }
                    }
                    c.commit();
                    System.out.println("已提交，共删除 " + total + " 行");
                } catch (SQLException e) {
                    c.rollback();
                    System.out.println("失败，已回滚: " + e.getMessage());
                    throw e;
                }
            }
        }
    }

    static List<String> delisted(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT symbol FROM symbols WHERE status <> 'TRADING' ORDER BY symbol")) {
            while (r.next()) out.add(r.getString(1));
        }
        return out;
    }

    static long count(Connection c, String table, String in, List<String> syms) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE symbol IN (" + in + ")")) {
            for (int i = 0; i < syms.size(); i++) ps.setString(i + 1, syms.get(i));
            try (ResultSet r = ps.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }

    static long dumpTable(Connection c, String table, String in, List<String> syms, Writer w) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + table + " WHERE symbol IN (" + in + ")")) {
            for (int i = 0; i < syms.size(); i++) ps.setString(i + 1, syms.get(i));
            try (ResultSet r = ps.executeQuery()) {
                ResultSetMetaData m = r.getMetaData();
                int n = m.getColumnCount();
                StringBuilder head = new StringBuilder("INSERT INTO " + table + " (");
                for (int i = 1; i <= n; i++) head.append(i > 1 ? "," : "").append("`").append(m.getColumnLabel(i)).append("`");
                head.append(") VALUES ");
                long rows = 0;
                while (r.next()) {
                    StringBuilder sb = new StringBuilder(head);
                    sb.append("(");
                    for (int i = 1; i <= n; i++) {
                        if (i > 1) sb.append(",");
                        String v = r.getString(i);
                        if (v == null) sb.append("NULL");
                        else sb.append('\'').append(v.replace("\\", "\\\\").replace("'", "\\'")
                                .replace("\n", "\\n").replace("\r", "\\r")).append('\'');
                    }
                    sb.append(");\n");
                    w.write(sb.toString());
                    rows++;
                }
                System.out.printf("  %-20s 导出 %d 行%n", table, rows);
                return rows;
            }
        }
    }
}
