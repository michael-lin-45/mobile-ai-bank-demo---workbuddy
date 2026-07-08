import java.sql.*;
public class H2Clean2 {
  public static void main(String[] args) throws Exception {
    Class.forName("org.h2.Driver");
    String url = "jdbc:h2:file:" + args[0].replace("\\","/") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
    Connection conn = DriverManager.getConnection(url, "sa", "");
    Statement st = conn.createStatement();

    String today = "2026-07-06";

    // sessions: delete by start_time
    try { int d = st.executeUpdate("DELETE FROM sessions WHERE CAST(start_time AS DATE) < '" + today + "'"); System.out.println("sessions deleted: " + d); } catch (Exception e) { System.out.println("sessions: " + e.getMessage()); }

    // session_turns: delete by timestamp
    try { int d = st.executeUpdate("DELETE FROM session_turns WHERE CAST(timestamp AS DATE) < '" + today + "'"); System.out.println("session_turns deleted: " + d); } catch (Exception e) { System.out.println("session_turns: " + e.getMessage()); }

    // metrics_agg: batch delete (4.5M rows, delete in batches of 100k)
    System.out.println("Deleting metrics_agg in batches...");
    int totalDeleted = 0;
    while (true) {
      int d = st.executeUpdate("DELETE FROM metrics_agg WHERE id IN (SELECT id FROM metrics_agg WHERE CAST(timestamp AS DATE) < '" + today + "' LIMIT 100000)");
      totalDeleted += d;
      System.out.println("  batch deleted: " + d + " (total: " + totalDeleted + ")");
      if (d == 0) break;
    }
    System.out.println("metrics_agg total deleted: " + totalDeleted);

    // agent_performance: delete by timestamp
    try { int d = st.executeUpdate("DELETE FROM agent_performance WHERE CAST(timestamp AS DATE) < '" + today + "'"); System.out.println("agent_performance deleted: " + d); } catch (Exception e) { System.out.println("agent_performance: " + e.getMessage()); }

    // token_cost: delete by timestamp
    try { int d = st.executeUpdate("DELETE FROM token_cost WHERE CAST(timestamp AS DATE) < '" + today + "'"); System.out.println("token_cost deleted: " + d); } catch (Exception e) { System.out.println("token_cost: " + e.getMessage()); }

    // tool_calls: delete by timestamp
    try { int d = st.executeUpdate("DELETE FROM tool_calls WHERE CAST(timestamp AS DATE) < '" + today + "'"); System.out.println("tool_calls deleted: " + d); } catch (Exception e) { System.out.println("tool_calls: " + e.getMessage()); }

    // Show final counts
    String[] tables = {"spans", "logs", "sessions", "session_turns", "metrics_agg", "agent_performance", "token_cost", "tool_calls"};
    System.out.println("\n=== AFTER CLEANUP ===");
    for (String t : tables) {
      try {
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t);
        if (rs.next()) System.out.println("  " + t + ": " + rs.getLong(1) + " rows");
      } catch (Exception e) { System.out.println("  " + t + ": ERROR"); }
    }

    // Compact
    System.out.println("\nCompacting database...");
    st.execute("SHUTDOWN COMPACT");
    System.out.println("Done!");
    conn.close();
  }
}