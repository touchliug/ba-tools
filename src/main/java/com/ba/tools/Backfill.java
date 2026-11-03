package com.ba.tools;

import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;

/**
 * BackfillKlines —— 把币安 USDT 永续的 K 线历史补到 N 年(默认 3 年), 直接 UPSERT 进 MySQL `klines` 表。
 *
 * 设计要点:
 *  - 幂等: INSERT ... ON DUPLICATE KEY UPDATE, 可反复跑; 已有覆盖的 symbol 自动跳过(除非 --force)。
 *  - 只增量: 先查该 symbol 在库里的 MIN(open_time), 只补 "缺口" 那一段; 完全没有则补满 N 年。
 *  - 限速: 全局令牌桶(默认 4 req/s) + 读 X-MBX-USED-WEIGHT-1M 主动退避, 避免 429/418。
 *  - 429 按 Retry-After 退避; 418(IP 封禁) 长睡一次, 再犯则中止。
 *  - 零外部依赖: 自带极简 JSON 解析器; 只需 MySQL JDBC 驱动在 classpath。
 *  - 只读币安, 只写 klines 表; 不 ALTER、不 DELETE(除 --selftest 自己造的测试行)。
 *
 * 用法:
 *   java -jar ba-tools.jar backfill --db-pass 'xxx' --years 3 [--proxy 127.0.0.1:7897]
 *
 * 常用参数:
 *   --db-host 43.155.216.33   --db-port 3306   --db-name ba   --db-user root
 *   --db-pass <密码>          (或用环境变量 DB_PASSWORD)
 *   --years 3                 补多少年(按 365 天/年算)
 *   --interval 1d             1d | 4h | 1h | 15m | 5m (注意: 1h 的请求量是 1d 的 ~18 倍)
 *   --proxy 127.0.0.1:7897    需要代理才能访问币安时用
 *   --base-url https://fapi.binance.com
 *   --symbols BTCUSDT,ETHUSDT 只补指定币
 *   --max-symbols 5           只跑前 N 个(冒烟测试)
 *   --check                   只体检: 连库、列 symbol、统计现有覆盖天数, 不访问币安
 *   --selftest                只测数据库写入通路(造 3 行测试数据后删除)
 *   --dry-run                 访问币安但不写库
 *   --force                   忽略"已覆盖"判断, 强制重拉
 *   --rps 4                   全局请求速率上限
 */
public final class Backfill {

    // ---------- 配置(可被命令行覆盖) ----------
    static String dbHost = "43.155.216.33";
    static int dbPort = 3306;
    static String dbName = "ba", dbUser = "root", dbPass = "";
    static int years = 3;
    static String interval = "1d";
    static String proxyHost = null;
    static int proxyPort = 0;
    static String baseUrl = "https://fapi.binance.com";
    static double rps = 3.0;   // limit=1500 时单次权重 10 -> 3 req/s ≈ 1800 权重/分, 留出余量
    static boolean dryRun = false, force = false, checkOnly = false, selftest = false;
    static List<String> onlySymbols = null;
    static int maxSymbols = 0;

    static final long DAY = 86_400_000L;
    static final int LIMIT = 1500;              // 币安 klines 单次上限
    static final String SELFTEST_SYM = "ZZSELFTEST";

    static HttpClient http;
    static long nextSlotNanos = 0;
    static int usedWeight = 0;
    static int bans = 0;

    static void run(String[] args) throws Exception {
        parseArgs(args);
        if (dbPass == null || dbPass.isEmpty()) {
            dbPass = System.getenv("DB_PASSWORD");
        }
        if (dbPass == null) dbPass = "";
        for (String a : args) if (a.startsWith("--db-pass=")) dbPass = a.substring(10);

        long now = System.currentTimeMillis();
        long targetStart = now - (long) years * 365 * DAY;
        long barMs = barMs(interval);

        String conn = "jdbc:mysql://" + dbHost + ":" + dbPort + "/" + dbName
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + "&connectTimeout=15000&socketTimeout=600000&rewriteBatchedStatements=true";

        log("DB   = %s", conn.replaceAll("password=[^&]*", ""));
        log("目标 = 最近 %d 年, interval=%s, 窗口起点 %s", years, interval, isoDay(targetStart));
        if (proxyHost != null) log("代理 = %s:%d", proxyHost, proxyPort);
        log("模式 = %s%s%s", checkOnly ? "CHECk(只体检) " : "", selftest ? "SELFTEST " : "", dryRun ? "DRY-RUN " : "");

        try (Connection cn = DriverManager.getConnection(conn, dbUser, dbPass)) {
            cn.setAutoCommit(true);
            log("已连接 MySQL, server=%s", cn.getMetaData().getDatabaseProductVersion());

            if (selftest) { selftest(cn, barMs); return; }

            List<String> symbols = resolveSymbols(cn);
            if (symbols.isEmpty()) { log("没有可补的 symbol, 退出"); return; }
            if (maxSymbols > 0 && symbols.size() > maxSymbols) symbols = symbols.subList(0, maxSymbols);
            log("待处理 symbol: %d 个", symbols.size());

            if (checkOnly) { check(cn, symbols, targetStart, barMs); return; }

            http = buildHttp();

            int done = 0, skipped = 0, failed = 0, empty = 0;
            long rows = 0;
            List<String> failList = new ArrayList<>();
            long t0 = System.currentTimeMillis();

            for (int i = 0; i < symbols.size(); i++) {
                String sym = symbols.get(i);
                try {
                    long existingMin = minOpenTime(cn, sym);
                    long start = targetStart;
                    if (!force && existingMin > 0) {
                        // 已有数据从 existingMin 开始; 只需补 existingMin 之前那一截
                        if (existingMin <= targetStart + 2 * barMs) { skipped++; continue; }
                        start = targetStart;
                        long end = existingMin - barMs;   // 补到已有数据前一格
                        if (end < start) { skipped++; continue; }
                        int n = fetchAndStore(cn, sym, start, end, barMs);
                        rows += n; done++;
                        if (n == 0) empty++;
                    } else {
                        int n = fetchAndStore(cn, sym, start, now, barMs);
                        rows += n; done++;
                        if (n == 0) empty++;
                    }
                } catch (Exception e) {
                    failed++;
                    failList.add(sym + ": " + e.getMessage());
                    log("  [FAIL] %s -> %s", sym, e.getMessage());
                }
                if ((i + 1) % 10 == 0 || i == symbols.size() - 1) {
                    long el = System.currentTimeMillis() - t0;
                    double eta = (el / (double) (i + 1)) * (symbols.size() - i - 1) / 1000.0;
                    log("进度 %d/%d  写入行=%d  跳过=%d  失败=%d  用时=%.0fs  ETA=%.0fs  usedWeight=%d",
                            i + 1, symbols.size(), rows, skipped, failed, el / 1000.0, eta, usedWeight);
                }
            }

            log("");
            log("==== 完成 ====");
            log("处理=%d  跳过(已覆盖)=%d  失败=%d  无数据(未上市/已下架)=%d  总写入行=%d  用时=%.0fs",
                    done, skipped, failed, empty, rows, (System.currentTimeMillis() - t0) / 1000.0);
            if (!failList.isEmpty()) {
                log("失败明细(前 20):");
                failList.stream().limit(20).forEach(s -> log("  " + s));
            }
            coverage(cn);
        }
    }

    // ==================== 业务 ====================

    /** 拉 [start,end] 的全部 bar 并 UPSERT, 返回写入行数。 */
    static int fetchAndStore(Connection cn, String sym, long start, long end, long barMs) throws Exception {
        List<String[]> rows = new ArrayList<>();
        long cursor = start;
        int guard = 0;
        while (cursor <= end && guard++ < 60) {
            String url = String.format(Locale.ROOT, "%s/fapi/v1/klines?symbol=%s&interval=%s&startTime=%d&endTime=%d&limit=%d",
                    baseUrl, sym, interval, cursor, end, LIMIT);
            String body = get(url);
            List<Object> arr = asList(Json.parse(body));
            if (arr.isEmpty()) break;
            for (Object o : arr) {
                List<Object> k = asList(o);
                rows.add(new String[]{
                        num(k.get(0)), num(k.get(1)), num(k.get(2)), num(k.get(3)), num(k.get(4)), num(k.get(5)),
                        num(k.get(6)), num(k.get(7)), num(k.get(8)), num(k.get(9)), num(k.get(10))
                });
            }
            if (arr.size() < LIMIT) break;
            long lastOpen = Long.parseLong(num(asList(arr.get(arr.size() - 1)).get(0)));
            cursor = lastOpen + barMs;
        }
        if (rows.isEmpty()) return 0;
        if (dryRun) { log("  [DRY] %s 将写入 %d 行 (%s .. %s)", sym, rows.size(),
                isoDay(Long.parseLong(rows.get(0)[0])), isoDay(Long.parseLong(rows.get(rows.size() - 1)[0]))); return 0; }

        String sql = "INSERT INTO klines (symbol, `interval`, open_time, open, high, low, close, volume, close_time,"
                + " quote_asset_volume, number_of_trades, taker_buy_base, taker_buy_quote)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE open=VALUES(open), high=VALUES(high), low=VALUES(low), close=VALUES(close),"
                + " volume=VALUES(volume), close_time=VALUES(close_time), quote_asset_volume=VALUES(quote_asset_volume),"
                + " number_of_trades=VALUES(number_of_trades), taker_buy_base=VALUES(taker_buy_base),"
                + " taker_buy_quote=VALUES(taker_buy_quote)";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            for (String[] r : rows) {
                ps.setString(1, sym);
                ps.setString(2, interval);
                ps.setLong(3, Long.parseLong(r[0]));
                ps.setString(4, r[1]); ps.setString(5, r[2]); ps.setString(6, r[3]); ps.setString(7, r[4]);
                ps.setString(8, r[5]);
                ps.setLong(9, Long.parseLong(r[6]));
                ps.setString(10, r[7]);
                ps.setInt(11, (int) Double.parseDouble(r[8]));
                ps.setString(12, r[9]); ps.setString(13, r[10]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return rows.size();
    }

    static long minOpenTime(Connection cn, String sym) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(
                "SELECT MIN(open_time) FROM klines WHERE symbol=? AND `interval`=?")) {
            ps.setString(1, sym); ps.setString(2, interval);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        }
    }

    /** 体检: 不访问币安, 只统计每个 symbol 现有多少天、缺口多少。 */
    static void check(Connection cn, List<String> symbols, long targetStart, long barMs) throws SQLException {
        log("");
        log("=== 体检 (不访问币安) ===");
        try (Statement st = cn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) rows_, COUNT(DISTINCT symbol) syms, MIN(open_time) mn, MAX(open_time) mx"
                     + " FROM klines WHERE `interval`='" + interval + "'")) {
            if (rs.next()) {
                log("klines[%s]: 行=%d, symbol=%d, 最早=%s, 最新=%s",
                        interval, rs.getLong(1), rs.getInt(2), isoDay(rs.getLong(3)), isoDay(rs.getLong(4)));
            }
        }
        Map<String, Integer> days = new HashMap<>();
        try (PreparedStatement ps = cn.prepareStatement(
                "SELECT symbol, COUNT(*) FROM klines WHERE `interval`=? GROUP BY symbol")) {
            ps.setString(1, interval);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) days.put(rs.getString(1), rs.getInt(2)); }
        }
        int need = 0, ok = 0, listedLater = 0;
        long needBars = (System.currentTimeMillis() - targetStart) / barMs;
        for (String s : symbols) {
            int have = days.getOrDefault(s, 0);
            if (have >= needBars * 0.9) ok++;
            else if (have == 0) listedLater++;
            else need++;
        }
        log("目标窗口约 %.0f 根/币", (double) needBars);
        log("已达标=%d  需补=%d  库里完全没有=%d  合计=%d", ok, need, listedLater, symbols.size());
        log("预计请求数 ≈ %d (每次最多 %d 根), 按 %.1f req/s 约需 %.0f 秒",
                need + listedLater, LIMIT, rps, (need + listedLater) / rps);
    }

    static void coverage(Connection cn) throws SQLException {
        try (Statement st = cn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*), COUNT(DISTINCT symbol), MIN(open_time), MAX(open_time)"
                     + " FROM klines WHERE `interval`='" + interval + "'")) {
            if (rs.next()) log("覆盖检查: 行=%d symbol=%d 最早=%s 最新=%s",
                    rs.getLong(1), rs.getInt(2), isoDay(rs.getLong(3)), isoDay(rs.getLong(4)));
        }
    }

    /** 数据库写入自检: 造 3 行, 校验 upsert, 再删掉。 */
    static void selftest(Connection cn, long barMs) throws Exception {
        log("=== SELFTEST: 验证 klines 写入通路 ===");
        long base = 1_500_000_000_000L; // 2017 年, 远早于真实数据
        String sql = "INSERT INTO klines (symbol, `interval`, open_time, open, high, low, close, volume, close_time,"
                + " quote_asset_volume, number_of_trades, taker_buy_base, taker_buy_quote)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE open=VALUES(open), close=VALUES(close)";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            for (int i = 0; i < 3; i++) {
                ps.setString(1, SELFTEST_SYM); ps.setString(2, interval);
                ps.setLong(3, base + i * barMs);
                ps.setString(4, "1.0"); ps.setString(5, "1.1"); ps.setString(6, "0.9"); ps.setString(7, "1.05");
                ps.setString(8, "100"); ps.setLong(9, base + i * barMs + barMs - 1);
                ps.setString(10, "105"); ps.setInt(11, 7); ps.setString(12, "50"); ps.setString(13, "52");
                ps.addBatch();
            }
            int[] r = ps.executeBatch();
            log("upsert 3 行 -> 返回 %d 个状态码", r.length);
        }
        try (PreparedStatement ps = cn.prepareStatement(
                "SELECT COUNT(*) FROM klines WHERE symbol=? AND `interval`=?")) {
            ps.setString(1, SELFTEST_SYM); ps.setString(2, interval);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                log("库内校验行数 = %d (期望 3)", rs.getInt(1));
                if (rs.getInt(1) != 3) throw new IllegalStateException("写入行数不符, 事务/权限可能有问题");
            }
        }
        // 再 upsert 一次(幂等性验证)
        try (PreparedStatement ps = cn.prepareStatement(
                "INSERT INTO klines (symbol, `interval`, open_time, open, close) VALUES (?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE close=VALUES(close)")) {
            ps.setString(1, SELFTEST_SYM); ps.setString(2, interval); ps.setLong(3, base);
            ps.setString(4, "1.0"); ps.setString(5, "9.99");
            ps.executeUpdate();
        }
        try (PreparedStatement ps = cn.prepareStatement(
                "SELECT close FROM klines WHERE symbol=? AND `interval`=? AND open_time=?")) {
            ps.setString(1, SELFTEST_SYM); ps.setString(2, interval); ps.setLong(3, base);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                log("幂等 upsert 生效: close = %s (期望 9.99)", rs.getString(1));
            }
        }
        try (PreparedStatement ps = cn.prepareStatement("DELETE FROM klines WHERE symbol=?")) {
            ps.setString(1, SELFTEST_SYM);
            int d = ps.executeUpdate();
            log("清理测试行 %d 条", d);
        }
        log("SELFTEST PASS — 写入通路正常");
    }

    // ==================== 币安 HTTP ====================

    static HttpClient buildHttp() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxyHost != null && proxyPort > 0) {
            b.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        return b.build();
    }

    static String get(String url) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            rateLimit();
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "ba-sync-backfill/1.0")
                        .GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = resp.statusCode();
                String w = resp.headers().firstValue("X-MBX-USED-WEIGHT-1M").orElse(null);
                if (w != null) {
                    try { usedWeight = Integer.parseInt(w.trim()); } catch (NumberFormatException ignore) {}
                    if (usedWeight > 1900) { log("  [限速] usedWeight=%d, 全局暂停 4s", usedWeight); Thread.sleep(4000); }
                }
                if (code == 200) return resp.body();
                if (code == 429) {
                    long wait = resp.headers().firstValue("Retry-After").map(Long::parseLong).orElse(5L) * 1000L;
                    log("  429 限流, 等待 %ds", wait / 1000);
                    Thread.sleep(Math.max(1000, wait));
                    continue;
                }
                if (code == 418) {
                    bans++;
                    if (bans >= 2) throw new IOException("IP 已被 418 封禁两次, 中止");
                    log("  418 IP 封禁, 等待 120s 后重试");
                    Thread.sleep(120_000);
                    continue;
                }
                if (code >= 500) { Thread.sleep(1000L * attempt); continue; }
                throw new IOException("HTTP " + code + " : " + abbreviate(resp.body(), 200));
            } catch (IOException | InterruptedException e) {
                last = e;
                if (e instanceof InterruptedException) throw e;
                Thread.sleep(800L * attempt);
            }
        }
        throw new IOException("请求失败: " + url + " -> " + (last == null ? "unknown" : last.getMessage()));
    }

    /** 全局令牌桶: 保证平均速率不超过 rps。 */
    static synchronized void rateLimit() throws InterruptedException {
        long now = System.nanoTime();
        long intervalNanos = (long) (1_000_000_000L / rps);
        if (nextSlotNanos == 0) nextSlotNanos = now;
        long wait = nextSlotNanos - now;
        nextSlotNanos = Math.max(now, nextSlotNanos) + intervalNanos;
        if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
    }

    // ==================== symbol 列表 ====================

    static List<String> resolveSymbols(Connection cn) throws Exception {
        if (onlySymbols != null) return onlySymbols;
        List<String> out = new ArrayList<>();
        if (checkOnly) {   // 体检模式不访问币安, 直接用库里的 symbol 表
            try (Statement st = cn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT symbol FROM symbols ORDER BY symbol")) {
                while (rs.next()) out.add(rs.getString(1));
            }
            log("数据库 symbols 表取到 %d 个", out.size());
            return out;
        }
        try {
            if (http == null) http = buildHttp();
            String body = get(baseUrl + "/fapi/v1/exchangeInfo");
            Map<String, Object> root = asMap(Json.parse(body));
            for (Object o : asList(root.get("symbols"))) {
                Map<String, Object> m = asMap(o);
                if ("PERPETUAL".equals(str(m.get("contractType")))
                        && "USDT".equals(str(m.get("quoteAsset")))
                        && "TRADING".equals(str(m.get("status")))) {
                    out.add(str(m.get("symbol")));
                }
            }
            Collections.sort(out);
            log("exchangeInfo 取到 %d 个 USDT 永续(TRADING)", out.size());
        } catch (Exception e) {
            log("exchangeInfo 失败(%s), 回退到数据库 symbols 表", e.getMessage());
        }
        if (out.isEmpty()) {
            try (Statement st = cn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT symbol FROM symbols ORDER BY symbol")) {
                while (rs.next()) out.add(rs.getString(1));
            }
            log("数据库 symbols 表取到 %d 个", out.size());
        }
        return out;
    }

    // ==================== 工具 ====================

    static long barMs(String iv) {
        return switch (iv) {
            case "1m" -> 60_000L; case "3m" -> 180_000L; case "5m" -> 300_000L; case "15m" -> 900_000L;
            case "30m" -> 1_800_000L; case "1h" -> 3_600_000L; case "4h" -> 14_400_000L; case "1d" -> DAY;
            default -> DAY;
        };
    }

    static String num(Object o) {
        if (o instanceof BigDecimal bd) return bd.toPlainString();
        return String.valueOf(o);
    }
    static String str(Object o) { return o == null ? null : String.valueOf(o); }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        if (o == null) return List.of();
        if (o instanceof List) return (List<Object>) o;
        throw new IllegalStateException("期望数组, 实际: " + o.getClass().getSimpleName());
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new IllegalStateException("期望对象, 实际: " + String.valueOf(o));
    }

    static String isoDay(long ms) {
        return java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
    }
    static String abbreviate(String s, int n) { return s == null ? "null" : s.length() <= n ? s : s.substring(0, n) + "..."; }

    static void log(String fmt, Object... a) {
        System.out.printf(Locale.ROOT, "[%s] %s%n",
                java.time.LocalTime.now().withNano(0), a.length == 0 ? fmt : String.format(Locale.ROOT, fmt, a));
        System.out.flush();
    }

    static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--db-host" -> dbHost = args[++i];
                case "--db-port" -> dbPort = Integer.parseInt(args[++i]);
                case "--db-name" -> dbName = args[++i];
                case "--db-user" -> dbUser = args[++i];
                case "--db-pass" -> dbPass = args[++i];
                case "--years" -> years = Integer.parseInt(args[++i]);
                case "--interval" -> interval = args[++i];
                case "--base-url" -> baseUrl = args[++i];
                case "--proxy" -> { String[] p = args[++i].split(":"); proxyHost = p[0]; proxyPort = Integer.parseInt(p[1]); }
                case "--rps" -> rps = Double.parseDouble(args[++i]);
                case "--symbols" -> onlySymbols = Arrays.asList(args[++i].split(","));
                case "--max-symbols" -> maxSymbols = Integer.parseInt(args[++i]);
                case "--check" -> checkOnly = true;
                case "--selftest" -> selftest = true;
                case "--dry-run" -> dryRun = true;
                case "--force" -> force = true;
                case "-h", "--help" -> { System.out.println(HELP); System.exit(0); }
                default -> {
                    if (a.startsWith("--db-pass=")) dbPass = a.substring(10);
                    else if (a.startsWith("--proxy=")) { String[] p = a.substring(8).split(":"); proxyHost = p[0]; proxyPort = Integer.parseInt(p[1]); }
                }
            }
        }
    }

    static final String HELP = """
        用法: ba-tools backfill [选项]
          --db-host/--db-port/--db-name/--db-user/--db-pass
          --years 3          --interval 1d|4h|1h|15m|5m
          --proxy host:port  --base-url URL   --rps 3
          --symbols A,B      --max-symbols N
          --check            只体检(连库+统计, 不访问币安)
          --selftest         只测数据库写入通路
          --dry-run          拉数据但不写库
          --force            忽略已覆盖判断
        """;

    // ==================== 极简 JSON 解析(够用: 对象/数组/字符串/数字/布尔/null) ====================
    static final class Json {
        private final String s; private int i = 0;
        private Json(String s) { this.s = s; }
        static Object parse(String s) {
            Json j = new Json(s);
            j.ws();
            Object v = j.value();
            j.ws();
            return v;
        }
        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private Object value() {
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> obj();
                case '[' -> arr();
                case '"' -> string();
                case 't' -> { i += 4; yield Boolean.TRUE; }
                case 'f' -> { i += 5; yield Boolean.FALSE; }
                case 'n' -> { i += 4; yield null; }
                default -> number();
            };
        }
        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); String k = string(); ws(); i++; ws(); // ':'
                m.put(k, value()); ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
            }
        }
        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value()); ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
            }
        }
        private String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(e);
                    }
                } else b.append(c);
            }
        }
        private BigDecimal number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return new BigDecimal(s.substring(st, i));
        }
    }
}
