import java.sql.*;
public class H2Query2 {
  public static void main(String[] args) throws Exception {
    Class.forName("org.h2.Driver");
    String url = "jdbc:h2:file:" + args[0].replace("\\","/") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE";
    Connection conn = DriverManager.getConnection(url, "sa", "");
    Statement st = conn.createStatement();

    System.out.println("=== SPANS COUNT ===");
    ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM spans");
    if (rs.next()) System.out.println("  total spans: " + rs.getLong(1));

    System.out.println("\n=== ROOT SPANS (parent_span_id IS NULL OR empty) ===");
    rs = st.executeQuery("SELECT COUNT(*) FROM spans WHERE parent_span_id IS NULL OR parent_span_id = ''");
    if (rs.next()) System.out.println("  root spans: " + rs.getLong(1));

    System.out.println("\n=== SPANS BY OPERATION (top 30) ===");
    rs = st.executeQuery("SELECT operation_name, COUNT(*) as cnt FROM spans GROUP BY operation_name ORDER BY cnt DESC LIMIT 30");
    while (rs.next()) System.out.println("  [" + rs.getLong(2) + "] " + rs.getString(1));

    System.out.println("\n=== RECENT 10 ROOT SPANS ===");
    rs = st.executeQuery("SELECT trace_id, span_id, operation_name, start_time, parent_span_id FROM spans WHERE parent_span_id IS NULL OR parent_span_id = '' ORDER BY start_time DESC LIMIT 10");
    while (rs.next()) {
      System.out.println("  trace=" + rs.getString("trace_id") + " | op=" + rs.getString("operation_name") + " | start=" + rs.getString("start_time"));
    }

    System.out.println("\n=== TIME RANGE ===");
    rs = st.executeQuery("SELECT MIN(start_time), MAX(start_time) FROM spans");
    if (rs.next()) System.out.println("  min=" + rs.getString(1) + " max=" + rs.getString(2));

    System.out.println("\n=== SESSIONS COUNT ===");
    try {
      rs = st.executeQuery("SELECT COUNT(*) FROM sessions");
      if (rs.next()) System.out.println("  total sessions: " + rs.getLong(1));
    } catch (Exception e) { System.out.println("  sessions table err: " + e.getMessage()); }

    System.out.println("\n=== RECENT 10 SESSIONS ===");
    try {
      rs = st.executeQuery("SELECT session_id, user_id, agent_chain, intent, status, created_at FROM sessions ORDER BY created_at DESC LIMIT 10");
      while (rs.next()) {
        System.out.println("  sess=" + rs.getString("session_id") + " | user=" + rs.getString("user_id") + " | chain=" + rs.getString("agent_chain") + " | intent=" + rs.getString("intent") + " | status=" + rs.getString("status") + " | created=" + rs.getString("created_at"));
      }
    } catch (Exception e) { System.out.println("  sessions query err: " + e.getMessage()); }

    conn.close();
  }
}