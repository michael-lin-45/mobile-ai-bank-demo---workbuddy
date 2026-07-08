package com.observability.repository;

import com.observability.model.TokenCost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * TokenCost Repository — Token 成本明细查询
 */
@Repository
public interface TokenCostRepository extends JpaRepository<TokenCost, Long> {

    /**
     * 按 intent 和时间范围查询
     */
    @Query("SELECT t FROM TokenCost t WHERE " +
           "t.intent = :intent " +
           "AND t.timestamp BETWEEN :from AND :to " +
           "ORDER BY t.timestamp ASC")
    List<TokenCost> findByIntentAndTimeRange(@Param("intent") String intent,
                                               @Param("from") Instant from,
                                               @Param("to") Instant to);

    /**
     * 按时间范围查询所有记录
     */
    @Query("SELECT t FROM TokenCost t WHERE " +
           "t.timestamp BETWEEN :from AND :to " +
           "ORDER BY t.timestamp ASC")
    List<TokenCost> findByTimeRange(@Param("from") Instant from,
                                      @Param("to") Instant to);

    /**
     * 按 model 分组 — 获取时间范围内所有不同 model 的记录
     */
    @Query("SELECT t FROM TokenCost t WHERE " +
           "t.timestamp BETWEEN :from AND :to " +
           "ORDER BY t.model, t.timestamp ASC")
    List<TokenCost> findByTimeRangeOrderByModel(@Param("from") Instant from,
                                                  @Param("to") Instant to);

    /**
     * 按 intent 分组 — 获取时间范围内所有不同 intent 的记录
     */
    @Query("SELECT t FROM TokenCost t WHERE " +
           "t.timestamp BETWEEN :from AND :to " +
           "ORDER BY t.intent, t.timestamp ASC")
    List<TokenCost> findByTimeRangeOrderByIntent(@Param("from") Instant from,
                                                   @Param("to") Instant to);
}
