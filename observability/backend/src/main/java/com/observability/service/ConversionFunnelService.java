package com.observability.service;

import com.observability.model.Session;
import com.observability.repository.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * 转化漏斗分析服务
 */
@Slf4j
@Service
public class ConversionFunnelService {

    private final SessionRepository sessionRepository;

    public ConversionFunnelService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * 获取转化漏斗数据
     *
     * @param from 开始时间
     * @param to   结束时间
     */
    public Map<String, Object> getFunnel(Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();

        List<Session> sessions = sessionRepository.findByTimeRange(from, to,
                org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE)).getContent();

        // 漏斗阶段: 进入会话 → 意图识别 → L1路由 → L2执行 → 业务完成
        long totalSessions = sessions.size();
        long intentRecognized = 0;
        long l1Routed = 0;
        long l2Executed = 0;
        long businessCompleted = 0;

        for (Session s : sessions) {
            String flow = s.getIntentFlow();
            String status = s.getStatus();

            if (flow != null && !flow.isEmpty()) {
                intentRecognized++; // 有意图流即表示意图已识别
                if (flow.contains("L1")) l1Routed++;
                if (flow.contains("L2")) l2Executed++;
            }
            if ("completed".equals(status)) {
                businessCompleted++;
            }
        }

        // 避免零除
        if (totalSessions == 0) totalSessions = 1;

        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(Map.of("stage", "进入会话", "count", totalSessions, "rate", 100.0));
        stages.add(Map.of("stage", "意图识别", "count", Math.max(intentRecognized, totalSessions),
                "rate", Math.round(intentRecognized * 1000.0 / totalSessions) / 10.0));
        stages.add(Map.of("stage", "L1路由", "count", l1Routed,
                "rate", Math.round(l1Routed * 1000.0 / totalSessions) / 10.0));
        stages.add(Map.of("stage", "L2执行", "count", l2Executed,
                "rate", Math.round(l2Executed * 1000.0 / totalSessions) / 10.0));
        stages.add(Map.of("stage", "业务完成", "count", businessCompleted,
                "rate", Math.round(businessCompleted * 1000.0 / totalSessions) / 10.0));

        // 流失节点
        List<Map<String, Object>> dropOffs = new ArrayList<>();
        long l1Drop = intentRecognized - l1Routed;
        long l2Drop = l1Routed - l2Executed;
        long bizDrop = l2Executed - businessCompleted;

        if (l1Drop > 0) {
            dropOffs.add(Map.of("stage", "意图识别→L1路由", "count", l1Drop,
                    "rate", Math.round(l1Drop * 1000.0 / Math.max(intentRecognized, 1)) / 10.0));
        }
        if (l2Drop > 0) {
            dropOffs.add(Map.of("stage", "L1路由→L2执行", "count", l2Drop,
                    "rate", Math.round(l2Drop * 1000.0 / Math.max(l1Routed, 1)) / 10.0));
        }
        if (bizDrop > 0) {
            dropOffs.add(Map.of("stage", "L2执行→业务完成", "count", bizDrop,
                    "rate", Math.round(bizDrop * 1000.0 / Math.max(l2Executed, 1)) / 10.0));
        }

        // 放弃率饼图数据: 把 dropOffs 映射为 [{ name: stage, value: count }]
        List<Map<String, Object>> abandonPie = new ArrayList<>();
        for (Map<String, Object> drop : dropOffs) {
            abandonPie.add(Map.of(
                    "name", drop.get("stage"),
                    "value", drop.get("count")));
        }

        // 漏斗明细: 基于 stages 逐阶段生成明细行
        List<Map<String, Object>> details = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            Map<String, Object> stageMap = stages.get(i);
            String stageName = (String) stageMap.get("stage");
            long entered = ((Number) stageMap.get("count")).longValue();
            long completed;
            if (i < stages.size() - 1) {
                completed = ((Number) stages.get(i + 1).get("count")).longValue();
            } else {
                completed = entered; // 末阶段完成数 = 自身
            }
            long abandoned = Math.max(0, entered - completed);

            String conversionRate;
            String abandonRate;
            if (entered == 0) {
                conversionRate = "—";
                abandonRate = "—";
            } else {
                conversionRate = String.format("%.1f%%", completed * 100.0 / entered);
                abandonRate = String.format("%.1f%%", abandoned * 100.0 / entered);
            }

            // 主要放弃原因: 在 dropOffs 中找以 "本阶段名→" 开头的那条
            String reason = "—";
            String prefix = stageName + "→";
            for (Map<String, Object> drop : dropOffs) {
                String dropStage = (String) drop.get("stage");
                if (dropStage.startsWith(prefix)) {
                    reason = dropStage.substring(prefix.length());
                    break;
                }
            }

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("stage", stageName);
            detail.put("entered", entered);
            detail.put("completed", completed);
            detail.put("abandoned", abandoned);
            detail.put("conversionRate", conversionRate);
            detail.put("abandonRate", abandonRate);
            detail.put("reason", reason);
            details.add(detail);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stages", stages);
        result.put("dropOffs", dropOffs);
        result.put("abandonPie", abandonPie);
        result.put("details", details);
        return result;
    }
}
