package com.observability.service;

import com.observability.model.AlertEvent;
import com.observability.model.AlertRule;
import com.observability.repository.AlertEventRepository;
import com.observability.repository.AlertRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * 告警服务 — 规则 CRUD + 事件查询
 */
@Slf4j
@Service
public class AlertService {

    private final AlertRuleRepository alertRuleRepository;
    private final AlertEventRepository alertEventRepository;

    public AlertService(AlertRuleRepository alertRuleRepository,
                        AlertEventRepository alertEventRepository) {
        this.alertRuleRepository = alertRuleRepository;
        this.alertEventRepository = alertEventRepository;
    }

    // ==================== 规则 CRUD ====================

    /** 获取所有告警规则 */
    public List<AlertRule> listRules() {
        return alertRuleRepository.findAll();
    }

    /** 获取单条规则 */
    public AlertRule getRule(Long id) {
        return alertRuleRepository.findById(id).orElse(null);
    }

    /** 创建规则 */
    @Transactional
    public AlertRule createRule(AlertRule rule) {
        if (rule.getEnabled() == null) rule.setEnabled(true);
        if (rule.getSeverity() == null) rule.setSeverity("WARNING");
        log.info("[Alert] Creating rule: {}", rule.getRuleName());
        return alertRuleRepository.save(rule);
    }

    /** 更新规则 */
    @Transactional
    public AlertRule updateRule(Long id, AlertRule update) {
        AlertRule existing = alertRuleRepository.findById(id).orElse(null);
        if (existing == null) return null;

        if (update.getRuleName() != null) existing.setRuleName(update.getRuleName());
        if (update.getMetricName() != null) existing.setMetricName(update.getMetricName());
        if (update.getThreshold() != null) existing.setThreshold(update.getThreshold());
        if (update.getOperator() != null) existing.setOperator(update.getOperator());
        if (update.getDurationSeconds() != null) existing.setDurationSeconds(update.getDurationSeconds());
        if (update.getSeverity() != null) existing.setSeverity(update.getSeverity());
        if (update.getEnabled() != null) existing.setEnabled(update.getEnabled());
        if (update.getNotifyChannels() != null) existing.setNotifyChannels(update.getNotifyChannels());
        if (update.getEvaluationInterval() != null) existing.setEvaluationInterval(update.getEvaluationInterval());

        log.info("[Alert] Updating rule id={}: {}", id, existing.getRuleName());
        return alertRuleRepository.save(existing);
    }

    /** 获取单条事件 */
    public AlertEvent getEvent(Long id) {
        return alertEventRepository.findById(id).orElse(null);
    }

    /** 确认告警事件（状态机 FIRING/ACKED → ACKED） */
    @Transactional
    public AlertEvent ackEvent(Long id) {
        AlertEvent event = alertEventRepository.findById(id).orElse(null);
        if (event == null) return null;
        event.setStatus("ACKED");
        log.info("[Alert] Event id={} acknowledged", id);
        return alertEventRepository.save(event);
    }

    /** 删除规则 */
    @Transactional
    public boolean deleteRule(Long id) {
        if (!alertRuleRepository.existsById(id)) return false;
        alertRuleRepository.deleteById(id);
        log.info("[Alert] Deleted rule id={}", id);
        return true;
    }

    // ==================== 事件查询 ====================

    /**
     * 查询告警事件（分页 + 筛选）
     */
    public Map<String, Object> listEvents(Long ruleId, String status, Instant from, Instant to,
                                           int page, int size) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();
        if (size <= 0) size = 20;
        if (size > 200) size = 200;
        if (page < 0) page = 0;

        PageRequest pageable = PageRequest.of(page, size);
        Page<AlertEvent> result;

        if (ruleId != null) {
            result = alertEventRepository.findByRuleIdAndTimeRange(ruleId, from, to, pageable);
        } else {
            result = alertEventRepository.findByTimeRange(from, to, pageable);
        }

        // 内存筛选 status（H2 处理多条件较复杂）
        List<Map<String, Object>> content = new ArrayList<>();
        for (AlertEvent event : result.getContent()) {
            if (status != null && !status.isBlank() && !status.equals(event.getStatus())) {
                continue;
            }
            content.add(toEventVO(event));
        }

        return Map.of(
                "content", content,
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "number", page
        );
    }

    private Map<String, Object> toEventVO(AlertEvent event) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", event.getId());
        vo.put("ruleId", event.getRuleId());
        vo.put("ruleName", event.getRuleName());
        vo.put("severity", event.getSeverity());
        vo.put("status", event.getStatus());
        vo.put("firedAt", event.getTriggeredAt() != null ? event.getTriggeredAt().toString() : null);
        vo.put("resolvedAt", event.getResolvedAt() != null ? event.getResolvedAt().toString() : null);
        vo.put("message", event.getMessage());
        // T-B DDL 增强字段透出
        vo.put("notifiedChannels", event.getNotifiedChannels());
        vo.put("notifyResult", event.getNotifyResult());
        vo.put("suppressionCount", event.getSuppressionCount());
        return vo;
    }
}
