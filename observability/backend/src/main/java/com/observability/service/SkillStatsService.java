package com.observability.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Skill 业务效果统计服务 — P0 跳过，P1 实现
 *
 * ⚠️ 仅空壳类，标注 @Deprecated。
 * 所有方法返回 501 Not Implemented 错误。
 */
@Slf4j
@Service
@Deprecated
public class SkillStatsService {

    /**
     * P1 实现：获取 Skill 业务效果统计
     */
    public String getSkillStats() {
        log.debug("[SkillStats] P0 not implemented");
        return "";
    }
}
