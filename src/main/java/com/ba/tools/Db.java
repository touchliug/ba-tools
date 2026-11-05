package com.ba.tools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** 数据库连接工具：统一 URL 形态与密码来源（--db-pass 优先，其次环境变量 DB_PASSWORD）。 */
final class Db {

    private Db() {}

    static String url(String host, int port, String name) {
        return "jdbc:mysql://" + host + ":" + port + "/" + name
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
                + "&connectTimeout=20000&socketTimeout=600000&rewriteBatchedStatements=true&autoReconnect=true&tcpKeepAlive=true";
    }

    static Connection open(Args a) throws SQLException {
        String host = a.get("db-host", System.getenv().getOrDefault("DB_HOST", "43.155.216.33"));
        int port = a.getInt("db-port", 3306);
        String name = a.get("db-name", System.getenv().getOrDefault("DB_NAME", "ba"));
        String user = a.get("db-user", System.getenv().getOrDefault("DB_USERNAME", "root"));
        String pass = a.get("db-pass", pass());
        return DriverManager.getConnection(url(host, port, name), user, pass);
    }

    /** 密码来源：DB_PASSWORD 优先，其次兼容旧变量 DB_PASS。 */
    static String pass() {
        String p = System.getenv("DB_PASSWORD");
        if (p == null || p.isEmpty()) p = System.getenv("DB_PASS");
        if (p == null || p.isEmpty()) {
            throw new IllegalStateException("缺少数据库密码：用 --db-pass 传入，或设置环境变量 DB_PASSWORD");
        }
        return p;
    }
}
