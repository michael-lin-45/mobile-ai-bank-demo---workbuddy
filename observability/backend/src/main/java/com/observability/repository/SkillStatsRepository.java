package com.observability.repository;

import com.observability.model.SkillStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * SkillStats Repository — P0 跳过，P1 实现
 *
 * ⚠️ 仅空壳类，标注 @Deprecated。
 * P1 阶段建表 skill_stats 并实现实际查询方法。
 */
@Repository
@Deprecated
public interface SkillStatsRepository extends JpaRepository<SkillStats, Long> {
    // P1 实现：findBySkillName, findByIntent, findByTimeRange 等
}
