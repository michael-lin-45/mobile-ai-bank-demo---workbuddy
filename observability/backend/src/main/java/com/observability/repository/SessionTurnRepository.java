package com.observability.repository;

import com.observability.model.SessionTurn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * SessionTurn Repository — 会话轮次明细查询
 */
@Repository
public interface SessionTurnRepository extends JpaRepository<SessionTurn, Long> {

    /**
     * 按 sessionId 查询所有轮次，按 turnNumber 升序
     */
    List<SessionTurn> findBySessionIdOrderByTurnNumberAsc(String sessionId);

    /**
     * 按 traceId 查询轮次
     */
    List<SessionTurn> findByTraceId(String traceId);

    /**
     * 统计指定会话的轮次数
     */
    long countBySessionId(String sessionId);
}
