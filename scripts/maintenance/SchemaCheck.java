import java.sql.*;
public class SchemaCheck {
  public static void main(String[] a) throws Exception {
    String u="jdbc:mysql://" + System.getenv().getOrDefault("DB_HOST", "127.0.0.1") + ":3306/ba?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    try (Connection c=DriverManager.getConnection(u,System.getenv().getOrDefault("DB_USER","root"),a[0])) {
      try (ResultSet r=c.createStatement().executeQuery(
        "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ba' AND TABLE_NAME='symbols' ORDER BY ORDINAL_POSITION")) {
        System.out.print("symbols ?: ");
        while (r.next()) System.out.print(r.getString(1)+" ");
        System.out.println();
      }
      try (ResultSet r=c.createStatement().executeQuery(
        "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='ba' AND TABLE_NAME='purge_log'")) {
        r.next(); System.out.println("purge_log ???: " + (r.getInt(1)>0));
      }
      try (ResultSet r=c.createStatement().executeQuery("SELECT COUNT(*) FROM symbols")) {
        r.next(); System.out.println("symbols ??: " + r.getInt(1));
      }
    }
  }
}
