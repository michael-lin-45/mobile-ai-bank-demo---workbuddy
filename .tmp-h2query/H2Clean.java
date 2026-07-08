import java.sql.*;
public class H2Clean {
  public static void main(String[] args) throws Exception {
    Class.forName("org.h2.Driver");
    String url = "jdbc:h2:file:" + args[0].replace("\\","/") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
    Connection conn = DriverManager.getConnection(url, "sa", "");
    Statement st = conn.createStatement();

    // Show counts before cleanup
    String[] tables = {"spans", "logs", "sessions", "session_turns", "metrics_agg", "agent_performance", "token_cost", "tool_calls"};
    System.out.println("=== BEFORE CLEANUP ===");
    for (String t : tables) {
      try {
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t);
        if (rs.next()) System.out.println("  " + t + ": " + rs.getLong(1) + " rows");
      } catch (Exception e) { System.out.println("  " + t + ": ERROR " + e.getMessage()); }
    }

    // Delete data older than today (2026-07-06)
    String today = "2026-07-06";
    System.out.println("\n=== DELETING DATA BEFORE " + today + " ===");

    // spans: delete by start_time
    int spansDeleted = st.executeUpdate("DELETE FROM spans WHERE CAST(start_time AS DATE) < '" + today + "'");
    System.out.println("  spans deleted: " + spansDeleted);

    // logs: delete by timestamp
    try { int d = st.executeUpdate("DELETE FROM logs WHERE CAST(timestamp AS DATE) < '" + today + "'"); System.out.println("  logs deleted: " + d); } catch (Exception e) { System.out.println("  logs: " + e.getMessage()); }

    // sessions: delete by created_at
    try { int d = st.executeUpdate("DELETE FROM sessions WHERE CAST(created_at AS DATE) < '" + today + "'"); System.out.println("  sessions deleted: " + d); } catch (Exception e) { System.out.println("  sessions: " + e.getMessage()); }

    // session_turns: delete by created_at
    try { int d = st.executeUpdate("DELETE FROM session_turns WHERE CAST(created_at AS DATE) < '" + today + "'"); System.out.println("  session_turns deleted: " + d); } catch (Exception e) { System.out.println("  session_turns: " + e.getMessage()); }

    // metrics_agg: delete by timestamp
    try { int d = st.executeUpdate("DELETE FROM metrics_agg WHERE CAST(timestamp AS DATE) < '" + today + "'"); System.out.println("  metrics_agg deleted: " + d); } catch (Exception e) { System.out.println("  metrics_agg: " + e.getMessage()); }

    // agent_performance: delete by created_at
    try { int d = st.executeUpdate("DELETE FROM agent_performance WHERE CAST(created_at AS DATE) < '" + today + "'"); System.out.println("  agent_performance deleted: " + d); } catch (Exception e) { System.out.println("  agent_performance: " + e.getMessage()); }

    // token_cost: delete by created_at
    try { int d = st.executeUpdate("DELETE FROM token_cost WHERE CAST(created_at AS DATE) < '" + today + "'"); System.out.println("  token_cost deleted: " + d); } catch (Exception e) { System.out.println("  token_cost: " + e.getMessage()); }

    // tool_calls: delete by created_at
    try { int d = st.executeUpdate("DELETE FROM tool_calls WHERE CAST(created_at AS DATE) < '" + today + "'"); System.out.println("  tool_calls deleted: " + d); } catch (Exception e) { System.out.println("  tool_calls: " + e.getMessage()); }

    // Show counts after cleanup
    System.out.println("\n=== AFTER CLEANUP ===");
    for (String t : tables) {
      try {
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t);
        if (rs.next()) System.out.println("  " + t + ": " + rs.getLong(1) + " rows");
      } catch (Exception e) { System.out.println("  " + t + ": ERROR"); }
    }

    // Compact the database to reclaim space
    System.out.println("\n=== COMPACTING DATABASE ===");
    st.execute("SHUTDOWN COMPACT");
    System.out.println("  Done (database compacted and closed)");

    conn.close();
  }
}