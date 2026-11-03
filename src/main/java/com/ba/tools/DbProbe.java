package com.ba.tools;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 数据库体检：表规模、索引、以及几条典型查询的实测耗时。
 * 用来判断「哪条 SQL 会拖慢分析器」，也用来核对部署后的数据量。
 */
final class DbProbe {

    private DbProbe() {}

    static void run(Args a) throws Exception {
        String symbol = a.get("symbol", "BTCUSDT");
        try (Connection cn = Db.open(a)) {
            System.out.println("=== 表规模 ===");
            try (Statement st = cn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT table_name, table_rows, ROUND(data_length/1024/1024,1) mb, "
                       + "ROUND(index_length/1024/1024,1) idx_mb FROM information_schema.tables "
                       + "WHERE table_schema=DATABASE() ORDER BY data_length DESC")) {
                while (rs.next()) {
                    System.out.printf("%-18s rows≈%-9d data=%-7s MB index=%s MB%n",
                            rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4));
                }
            }

            System.out.println("\n=== 索引 ===");
            for (String t : new String[]{"klines", "open_interest", "long_short_ratio", "ticker_24h", "premium_index", "symbols"}) {
                StringBuilder sb = new StringBuilder();
                try (Statement st = cn.createStatement(); ResultSet rs = st.executeQuery("SHOW INDEX FROM " + t)) {
                    while (rs.next()) sb.append(rs.getString("Key_name")).append('(')
                            .append(rs.getString("Column_name")).append(") ");
                }
                System.out.printf("%-18s %s%n", t, sb.length() == 0 ? "<<无索引>>" : sb);
            }

            System.out.println("\n=== 典型查询耗时（3 次取中位）===");
            time(cn, "klines 单币最近 " + a.getInt("limit", 72) + " 根",
                    "SELECT * FROM klines WHERE symbol=? AND `interval`=? ORDER BY open_time DESC LIMIT ?",
                    symbol, a.get("interval", "1h"), a.getInt("limit", 72));
            time(cn, "open_interest 单币最近 " + a.getInt("limit", 72) + " 条",
                    "SELECT * FROM open_interest WHERE symbol=? AND period=? ORDER BY `timestamp` DESC LIMIT ?",
                    symbol, "1h", a.getInt("limit", 72));
            time(cn, "long_short_ratio 单币最近 " + a.getInt("limit", 72) + " 条",
                    "SELECT * FROM long_short_ratio WHERE symbol=? AND period=? ORDER BY `timestamp` DESC LIMIT ?",
                    symbol, "1h", a.getInt("limit", 72));
            time(cn, "ticker_24h 全表", "SELECT * FROM ticker_24h");
        }
    }

    private static void time(Connection cn, String label, String sql, Object... args) throws SQLException {
        List<Long> ts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            int n = count(cn, sql, args);
            ts.add((System.nanoTime() - t0) / 1_000_000);
            if (i == 0) label = label + "  [rows=" + n + "]";
        }
        Collections.sort(ts);
        System.out.printf("%-52s %d ms%n", label, ts.get(1));
    }

    private static int count(Connection cn, String sql, Object... args) throws SQLException {
        int n = 0;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) n++;
            }
        }
        return n;
    }
}
