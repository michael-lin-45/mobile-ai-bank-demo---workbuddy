import java.sql.*;
public class H2Query {
  public static void main(String[] args) throws Exception {
    Class.forName("org.h2.Driver");
    String url = "jdbc:h2:file:" + args[0].replace("\\","/") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;IFEXISTS=TRUE;AUTO_SERVER=FALSE;ACCESS_MODE_DATA=r";
    Connection conn = DriverManager.getConnection(url, "sa", "");
    Statement st = conn.createStatement();

    System.out.println("=== TABLES ===");
    ResultSet rs = st.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'");
    while (rs.next()) System.out.println("  " + rs.getString(1));

    System.out.println("\n=== SPANS COUNT ===");
    rs = st.executeQuery("SELECT COUNT(*) FROM spans");
    if (rs.next()) System.out.println("  total spans: " + rs.getLong(1));

    System.out.println("\n=== ROOT SPANS (parent_span_id IS NULL OR empty) ===");
    rs = st.executeQuery("SELECT COUNT(*) FROM spans WHERE parent_span_id IS NULL OR parent_span_id = ''");
    if (rs.next()) System.out.println("  root spans: " + rs.getLong(1));

    System.out.println("\n=== SPANS BY OPERATION (top 20) ===");
    rs = st.executeQuery("SELECT operation_name, COUNT(*) as cnt FROM spans GROUP BY operation_name ORDER BY cnt DESC LIMIT 20");
    while (rs.next()) System.out.println("  " + rs.getString(1) + " => " + rs.getLong(2));

    System.out.println("\n=== RECENT 5 ROOT SPANS ===");
    rs = st.executeQuery("SELECT trace_id, span_id, operation_name, start_time, parent_span_id FROM spans WHERE parent_span_id IS NULL OR parent_span_id = '' ORDER BY start_time DESC LIMIT 5");
    while (rs.next()) {
      System.out.println("  trace=" + rs.getString("trace_id") + " span=" + rs.getString("span_id") + " op=" + rs.getString("operation_name") + " start=" + rs.getString("start_time") + " parent=[" + rs.getString("parent_span_id") + "]");
    }

    System.out.println("\n=== TIME RANGE ===");
    rs = st.executeQuery("SELECT MIN(start_time), MAX(start_time) FROM spans");
    if (rs.next()) System.out.println("  min=" + rs.getString(1) + " max=" + rs.getString(2));

    conn.close();
  }
}