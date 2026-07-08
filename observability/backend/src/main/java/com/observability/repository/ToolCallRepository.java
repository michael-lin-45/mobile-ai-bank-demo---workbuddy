package com.observability.repository;

import com.observability.model.ToolCall;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * ToolCall Repository — 工具调用记录查询
 */
@Repository
public interface ToolCallRepository extends JpaRepository<ToolCall, Long> {

    /**
     * 按时间范围查询所有工具调用记录
     */
    @Query("SELECT t FROM ToolCall t WHERE " +
           "t.timestamp BETWEEN :from AND :to " +
           "ORDER BY t.toolName, t.timestamp ASC")
    List<ToolCall> findByTimeRange(@Param("from") Instant from,
                                     @Param("to") Instant to);

    /**
     * 按 toolName 和时间范围查询
     */
    @Query("SELECT t FROM ToolCall t WHERE " +
           "t.toolName = :toolName " +
           "AND t.timestamp BETWEEN :from AND :to " +
           "ORDER BY t.timestamp ASC")
    List<ToolCall> findByToolNameAndTimeRange(@Param("toolName") String toolName,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to);

    /**
     * 按 provider 和时间范围查询
     */
    @Query("SELECT t FROM ToolCall t WHERE " +
           "t.provider = :provider " +
           "AND t.timestamp BETWEEN :from AND :to " +
           "ORDER BY t.timestamp ASC")
    List<ToolCall> findByProviderAndTimeRange(@Param("provider") String provider,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to);
}
