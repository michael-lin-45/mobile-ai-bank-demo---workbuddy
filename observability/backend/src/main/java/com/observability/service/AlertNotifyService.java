package com.observability.service;

import com.observability.model.AlertEvent;
import com.observability.model.AlertRule;

/**
 * 告警通知抽象。遵守主理人决策 3：默认实现仅记录日志，不真实外发钉钉/飞书。
 */
public interface AlertNotifyService {

    /**
     * 通知分发。实现应将分发结果写回 event（notifiedChannels / notifyResult）。
     *
     * @param rule  触发规则（携带 notifyChannels）
     * @param event 待通知的告警事件（状态已为 FIRING）
     */
    void notify(AlertRule rule, AlertEvent event);
}
