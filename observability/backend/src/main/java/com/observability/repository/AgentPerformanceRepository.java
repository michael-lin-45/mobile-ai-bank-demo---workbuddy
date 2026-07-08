package com.observability.repository;

import com.observability.model.AgentPerformance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * AgentPerformance Repository — Agent/LLM 性能快照查询
 */
@Repository
public interface AgentPerformanceRepository extends JpaRepository<AgentPerformance, Long> {

    /**
     * 按 agentName 和时间范围查询（用于按 Agent 维度聚合）
     */
    @Query("SELECT a FROM AgentPerformance a WHERE " +
           "a.agentName = :agentName " +
           "AND a.timestamp BETWEEN :from AND :to " +
           "ORDER BY a.timestamp ASC")
    List<AgentPerformance> findByAgentNameAndTimeRange(@Param("agentName") String agentName,
                                                         @Param("from") Instant from,
                                                         @Param("to") Instant to);

    /**
     * 按时间范围查询所有记录
     */
    @Query("SELECT a FROM AgentPerformance a WHERE " +
           "a.timestamp BETWEEN :from AND :to " +
           "ORDER BY a.timestamp ASC")
    List<AgentPerformance> findByTimeRange(@Param("from") Instant from,
                                            @Param("to") Instant to);

    /**
     * 按 agentLevel 和时间范围查询
     */
    @Query("SELECT a FROM AgentPerformance a WHERE " +
           "a.agentLevel = :level " +
           "AND a.timestamp BETWEEN :from AND :to " +
           "ORDER BY a.timestamp ASC")
    List<AgentPerformance> findByAgentLevelAndTimeRange(@Param("level") String level,
                                                          @Param("from") Instant from,
                                                          @Param("to") Instant to);

    /**
     * 按 model 分组查询（用于 LLM 维度分析）
     */
    @Query("SELECT a FROM AgentPerformance a WHERE " +
           "a.timestamp BETWEEN :from AND :to " +
           "AND a.model IS NOT NULL " +
           "ORDER BY a.model, a.timestamp ASC")
    List<AgentPerformance> findByTimeRangeGroupByModel(@Param("from") Instant from,
                                                         @Param("to") Instant to);
}
