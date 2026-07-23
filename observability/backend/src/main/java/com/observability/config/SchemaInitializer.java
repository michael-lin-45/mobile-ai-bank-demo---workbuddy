package com.observability.config;

import com.observability.model.AlertRule;
import com.observability.model.BusinessEvent;
import com.observability.repository.AlertRuleRepository;
import com.observability.repository.BusinessEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
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

    @Autowired
    private BusinessEventRepository businessEventRepository;

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
        // V23 M1/M2：幂等种子业务埋点（业务引导办理 1,284 / 转人工 96，演示真值）
        seedBusinessEvents();
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

    /**
     * V23 M1/M2 业务埋点种子（§8.1.9 / 任务分解 B2）。
     * 幂等：business_events 非空则跳过。演示真值：mbank_card_click=1,284 / mbank_human_click=96。
     * 时间统一北京时间；card_type / source 按维度分布，供前端 groupBy 聚合校验。
     */
    private void seedBusinessEvents() {
        try {
            if (businessEventRepository.count() > 0) {
                return;
            }
            List<BusinessEvent> rows = new java.util.ArrayList<>();
            Instant base = Instant.now().minusSeconds(3L * 86400);
            String[] cardTypes = {"credit_card", "debit_card", "loan", "wealth"};
            String[] sources = {"chat_bar", "card_menu", "timeout"};
            String[] channels = {"app", "miniprogram"};

            // 业务引导办理 1,284
            for (int i = 0; i < 1284; i++) {
                BusinessEvent e = new BusinessEvent();
                e.setSessionId("s-card-" + i);
                e.setTraceId("t-card-" + i);
                e.setUserId("u-" + (i % 200));
                e.setAgent("bankAssistant");
                e.setEventType("mbank_card_click");
                e.setCardType(cardTypes[i % cardTypes.length]);
                e.setSource(null);
                e.setChannel(channels[i % channels.length]);
                e.setCreatedAt(base.plusSeconds((long) i * 120));
                rows.add(e);
            }
            // 转人工 96
            for (int i = 0; i < 96; i++) {
                BusinessEvent e = new BusinessEvent();
                e.setSessionId("s-human-" + i);
                e.setTraceId("t-human-" + i);
                e.setUserId("u-" + (i % 50));
                e.setAgent("bankAssistant");
                e.setEventType("mbank_human_click");
                e.setCardType(null);
                e.setSource(sources[i % sources.length]);
                e.setChannel(channels[i % channels.length]);
                e.setCreatedAt(base.plusSeconds((long) i * 1500));
                rows.add(e);
            }
            businessEventRepository.saveAll(rows);
            log.info("[SchemaInitializer] Seeded {} business_events (card_click=1284, human_click=96)", rows.size());
        } catch (Exception e) {
            log.warn("[SchemaInitializer] Failed to seed business_events: {}", e.getMessage());
        }
    }
}
