import java.sql.*;
import java.time.*;
public class AliveCheck {
  public static void main(String[] a) throws Exception {
    String u="jdbc:mysql://" + System.getenv().getOrDefault("DB_HOST", "127.0.0.1") + ":3306/ba?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    try (Connection c=DriverManager.getConnection(u,System.getenv().getOrDefault("DB_USER","root"),a[0])) {
      try (ResultSet r=c.createStatement().executeQuery("SELECT MAX(captured_at), COUNT(*) FROM ticker_24h")) {
        r.next();
        long t=r.getLong(1);
        System.out.println("ticker_24h ????: " + Instant.ofEpochMilli(t).atZone(ZoneOffset.ofHours(8)).toLocalDateTime() + "  (? " + r.getInt(2) + " ?)");
      }
      try (ResultSet r=c.createStatement().executeQuery("SELECT MAX(open_time) FROM klines WHERE `interval`='1h'")) {
        r.next();
        System.out.println("klines 1h ??: " + Instant.ofEpochMilli(r.getLong(1)).atZone(ZoneOffset.ofHours(8)).toLocalDateTime());
      }
      try (ResultSet r=c.createStatement().executeQuery("SELECT MAX(`timestamp`) FROM long_short_ratio WHERE period='1h'")) {
        r.next();
        System.out.println("long_short_ratio ??: " + Instant.ofEpochMilli(r.getLong(1)).atZone(ZoneOffset.ofHours(8)).toLocalDateTime());
      }
      System.out.println("????: " + LocalDateTime.now());
    }
  }
}
