package com.observability.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

/**
 * 定期清理旧数据，防止 H2 数据库文件无限膨胀导致启动缓慢。
 * 
 * 启动时执行一次（@PostConstruct），之后每天凌晨 3 点自动执行（@Scheduled）。
 * 默认保留 7 天数据。
 */
@Component
public class DataCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DataCleanupService.class);
    private static final int RETENTION_DAYS = 7;

    @Autowired
    private DataSource dataSource;

    /**
     * 表名与对应的时间列（用于 WHERE 条件过滤旧数据）
     */
    private static final String[][] TABLE_TIME_COLS = {
        {"spans",              "start_time"},
        {"logs",               "\"timestamp\""},
        {"sessions",           "start_time"},
        {"session_turns",      "\"timestamp\""},
        {"metrics_agg",        "\"timestamp\""},
        {"agent_performance",  "\"timestamp\""},
        {"token_cost",         "\"timestamp\""},
        {"tool_calls",         "\"timestamp\""},
        {"redis_metrics_snapshot", "\"timestamp\""},
    };

    @jakarta.annotation.PostConstruct
    public void cleanupOnStartup() {
        log.info("[DataCleanup] Starting startup cleanup, retention={} days", RETENTION_DAYS);
        Instant start = Instant.now();
        long totalDeleted = doCleanup();
        Duration elapsed = Duration.between(start, Instant.now());
        log.info("[DataCleanup] Startup cleanup completed: {} rows deleted in {} ms", totalDeleted, elapsed.toMillis());
    }

    @Scheduled(cron = "0 0 3 * * ?")  // 每天凌晨 3:00
    public void scheduledCleanup() {
        log.info("[DataCleanup] Starting scheduled cleanup");
        long totalDeleted = doCleanup();
        log.info("[DataCleanup] Scheduled cleanup completed: {} rows deleted", totalDeleted);
    }

    private long doCleanup() {
        long totalDeleted = 0;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            String cutoff = java.time.LocalDate.now().minusDays(RETENTION_DAYS).toString();

            for (String[] pair : TABLE_TIME_COLS) {
                String table = pair[0];
                String timeCol = pair[1];
                try {
                    // Count before
                    ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM " + table + " WHERE " + timeCol + " < '" + cutoff + "'");
                    rs.next();
                    long toDelete = rs.getLong(1);
                    rs.close();

                    if (toDelete > 0) {
                        int deleted = stmt.executeUpdate(
                            "DELETE FROM " + table + " WHERE " + timeCol + " < '" + cutoff + "'");
                        totalDeleted += deleted;
                        log.info("[DataCleanup] {}: {} rows deleted (had {} old records before cutoff {})",
                            table, deleted, toDelete, cutoff);
                    } else {
                        log.debug("[DataCleanup] {}: 0 old records, skipping", table);
                    }
                } catch (Exception e) {
                    log.warn("[DataCleanup] Failed to clean table {}: {}", table, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[DataCleanup] Fatal error during cleanup", e);
        }
        return totalDeleted;
    }
}
