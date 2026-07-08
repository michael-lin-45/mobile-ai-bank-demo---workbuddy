import java.sql.*;
public class H2Clean3 {
  public static void main(String[] args) throws Exception {
    Class.forName("org.h2.Driver");
    String url = "jdbc:h2:file:" + args[0].replace("\\","/") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
    Connection conn = DriverManager.getConnection(url, "sa", "");
    Statement st = conn.createStatement();

    // Fast approach: TRUNCATE metrics_agg (4.5M rows, too slow to DELETE)
    System.out.println("TRUNCATE metrics_agg...");
    st.execute("TRUNCATE TABLE metrics_agg");
    System.out.println("  Done");

    // Show final counts
    String[] tables = {"spans", "logs", "sessions", "session_turns", "metrics_agg", "agent_performance", "token_cost", "tool_calls"};
    System.out.println("\n=== FINAL COUNTS ===");
    for (String t : tables) {
      try {
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t);
        if (rs.next()) System.out.println("  " + t + ": " + rs.getLong(1) + " rows");
      } catch (Exception e) { System.out.println("  " + t + ": ERROR " + e.getMessage()); }
    }

    // Compact
    System.out.println("\nCompacting database...");
    st.execute("SHUTDOWN COMPACT");
    System.out.println("Done! Database compacted.");
    conn.close();
  }
}