package com.observability.config;

import com.observability.model.AlertRule;
import com.observability.repository.AlertRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/**
 * Ensures all tables in schema.sql exist at startup.
 * Since spring.sql.init.mode=never, we manually execute schema.sql
 * with CREATE TABLE IF NOT EXISTS statements (idempotent).
 */
@Slf4j
@Component
public class SchemaInitializer {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AlertRuleRepository alertRuleRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeSchema() {
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema.sql"));
            log.info("[SchemaInitializer] Schema initialization completed successfully");
        } catch (Exception e) {
            log.warn("[SchemaInitializer] Schema initialization failed (tables may already exist): {}", e.getMessage());
        }
        // T-B：幂等种子 5 条默认告警规则（schema 已建表，确保 table 存在后再写入）
        seedDefaultAlertRules();
    }

    /** T-B 默认 5 条规则种子（§3.2⑤）。幂等：已存在则跳过。 */
    private void seedDefaultAlertRules() {
        try {
            if (alertRuleRepository.count() > 0) {
                return;
            }
            List<AlertRule> defaults = List.of(
                    AlertRule.builder()
                            .ruleName("请求量突增(CRITICAL)")
                            .metricName("request_count").threshold(100000.0).operator(">")
                            .severity("CRITICAL").enabled(true).notifyChannels("dingtalk")
                            .evaluationInterval(30).build(),
                    AlertRule.builder()
                            .ruleName("LLM错误率过高(CRITICAL)")
                            .metricName("error_rate").threshold(50.0).operator(">")
                            .severity("CRITICAL").enabled(true).notifyChannels("feishu")
                            .evaluationInterval(30).build(),
                    AlertRule.builder()
                            .ruleName("P95延迟过高(WARNING)")
                            .metricName("latency_p95").threshold(5000.0).operator(">")
                            .severity("WARNING").enabled(true).notifyChannels("email")
                            .evaluationInterval(30).build(),
                    AlertRule.builder()
                            .ruleName("TTFT超时(WARNING)")
                            .metricName("ttft_p95").threshold(3000.0).operator(">")
                            .severity("WARNING").enabled(true).notifyChannels("dingtalk,feishu")
                            .evaluationInterval(30).build(),
                    AlertRule.builder()
                            .ruleName("满意度负面比率(WARNING)")
                            .metricName("unsatisfied_rate").threshold(50.0).operator(">")
                            .severity("WARNING").enabled(true).notifyChannels("email")
                            .evaluationInterval(30).build()
            );
            alertRuleRepository.saveAll(defaults);
            log.info("[SchemaInitializer] Seeded {} default alert rules", defaults.size());
        } catch (Exception e) {
            log.warn("[SchemaInitializer] Failed to seed default alert rules: {}", e.getMessage());
        }
    }
}
