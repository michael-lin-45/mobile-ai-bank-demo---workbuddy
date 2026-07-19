package com.observability.service;

import com.observability.model.AlertEvent;
import com.observability.model.AlertRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 默认告警通知实现（遵守主理人决策 3）。
 *
 * 仅把分发动作记录到日志（[Alert][NOTIFY]），并在 event 上回填
 * notifiedChannels / notifyResult，便于验收核查；绝不发起真实 HTTP 外发。
 */
@Slf4j
@Component
@Primary
public class LogNotifier implements AlertNotifyService {

    @Override
    public void notify(AlertRule rule, AlertEvent event) {
        String channels = rule.getNotifyChannels() != null ? rule.getNotifyChannels() : "none";
        event.setNotifiedChannels(rule.getNotifyChannels());
        event.setNotifyResult("LOGGED:" + channels + " (no real outbound per decision-3)");
        log.info("[Alert][NOTIFY][{}] rule={} channels={} msg={}",
                event.getStatus(), rule.getRuleName(), rule.getNotifyChannels(), event.getMessage());
    }
}
