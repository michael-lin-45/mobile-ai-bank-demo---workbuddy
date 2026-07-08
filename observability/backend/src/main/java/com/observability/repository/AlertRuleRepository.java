package com.observability.repository;

import com.observability.model.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * AlertRule Repository — 告警规则 CRUD
 */
@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    /**
     * 查询所有启用的规则
     */
    List<AlertRule> findByEnabledTrue();

    /**
     * 按严重级别查询
     */
    List<AlertRule> findBySeverity(String severity);

    /**
     * 按指标名查询
     */
    List<AlertRule> findByMetricName(String metricName);
}
