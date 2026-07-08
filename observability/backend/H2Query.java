import java.sql.*;

public class H2Query {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:file:D:/GitHub/mobile-ai-bank-demo - workbuddy/observability/backend/data/observability;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE";
        Connection conn = DriverManager.getConnection(url, "sa", "");
        Statement st = conn.createStatement();

        System.out.println("=== DISTINCT METRIC NAMES ===");
        ResultSet rs = st.executeQuery("SELECT DISTINCT metric_name FROM metrics_agg ORDER BY metric_name");
        while (rs.next()) {
            System.out.println("  " + rs.getString(1));
        }

        System.out.println("\n=== TOKEN METRICS (latest 5) ===");
        rs = st.executeQuery("SELECT metric_name, value, timestamp FROM metrics_agg WHERE metric_name LIKE '%token%' ORDER BY timestamp DESC LIMIT 5");
        while (rs.next()) {
            System.out.println("  " + rs.getString(1) + " = " + rs.getDouble(2) + " @ " + rs.getString(3));
        }

        System.out.println("\n=== CONFIDENCE/COMPLETNESS/ACCURACY METRICS COUNT ===");
        String[] names = {"agent.intent.confidence", "agent.extraction.completeness", "agent.rewrite.accuracy", "agent.intent.accuracy"};
        for (String name : names) {
            rs = st.executeQuery("SELECT COUNT(*) FROM metrics_agg WHERE metric_name = '" + name + "'");
            rs.next();
            System.out.println("  " + name + ": " + rs.getInt(1) + " rows");
        }

        System.out.println("\n=== REWRITE ACCURACY SAMPLE (latest 5) ===");
        rs = st.executeQuery("SELECT metric_name, value, tags, timestamp FROM metrics_agg WHERE metric_name = 'agent.rewrite.accuracy' ORDER BY timestamp DESC LIMIT 5");
        while (rs.next()) {
            System.out.println("  " + rs.getString(1) + " = " + rs.getDouble(2) + " tags=" + rs.getString(3) + " @ " + rs.getString(4));
        }

        System.out.println("\n=== AGENT.* METRIC COUNTS ===");
        rs = st.executeQuery("SELECT metric_name, COUNT(*) as cnt FROM metrics_agg WHERE metric_name LIKE 'agent.%' GROUP BY metric_name ORDER BY cnt DESC");
        while (rs.next()) {
            System.out.println("  " + rs.getString(1) + ": " + rs.getInt(2));
        }

        conn.close();
    }
}
