package com.observability.repository;

import com.observability.model.Session;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Session Repository — 会话聚合查询
 */
@Repository
public interface SessionRepository extends JpaRepository<Session, Long>, JpaSpecificationExecutor<Session> {

    /**
     * 按 sessionId 精确查询
     */
    Optional<Session> findBySessionId(String sessionId);

    /**
     * 按时间范围查询，分页
     */
    @Query("SELECT s FROM Session s WHERE " +
           "(s.startTime >= :from OR :from IS NULL) " +
           "AND (s.startTime <= :to OR :to IS NULL) " +
           "ORDER BY s.startTime DESC")
    Page<Session> findByTimeRange(@Param("from") Instant from,
                                  @Param("to") Instant to,
                                  Pageable pageable);

    /**
     * 按时间和状态查询
     */
    @Query("SELECT s FROM Session s WHERE " +
           "(s.startTime >= :from OR :from IS NULL) " +
           "AND (s.startTime <= :to OR :to IS NULL) " +
           "AND (s.status = :status OR :status IS NULL) " +
           "ORDER BY s.startTime DESC")
    Page<Session> findByTimeRangeAndStatus(@Param("from") Instant from,
                                            @Param("to") Instant to,
                                            @Param("status") String status,
                                            Pageable pageable);

    /**
     * 按时间范围查询（不分页，用于内存全量筛选）
     */
    @Query("SELECT s FROM Session s WHERE " +
           "(s.startTime >= :from OR :from IS NULL) " +
           "AND (s.startTime <= :to OR :to IS NULL) " +
           "ORDER BY s.startTime DESC")
    List<Session> findAllByTimeRange(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * 按时间和状态查询（不分页）
     */
    @Query("SELECT s FROM Session s WHERE " +
           "(s.startTime >= :from OR :from IS NULL) " +
           "AND (s.startTime <= :to OR :to IS NULL) " +
           "AND (s.status = :status OR :status IS NULL) " +
           "ORDER BY s.startTime DESC")
    List<Session> findAllByTimeRangeAndStatus(@Param("from") Instant from,
                                              @Param("to") Instant to,
                                              @Param("status") String status);

    /**
     * 按 userId 查询会话
     */
    Page<Session> findByUserIdOrderByStartTimeDesc(String userId, Pageable pageable);

    /**
     * 统计指定时间范围内的会话数
     */
    long countByStartTimeBetween(Instant from, Instant to);

    /**
     * 按状态 + 时间范围查询（分页）
     */
    Page<Session> findByStatusAndStartTimeBetween(@Param("status") String status,
                                                   @Param("from") Instant from,
                                                   @Param("to") Instant to,
                                                   Pageable pageable);

    /**
     * 按满意度评分查询低分会话
     */
    @Query("SELECT s FROM Session s WHERE s.satisfactionRating = :rating " +
           "AND (s.startTime >= :from OR :from IS NULL) " +
           "AND (s.startTime <= :to OR :to IS NULL) " +
           "ORDER BY s.startTime DESC")
    Page<Session> findBySatisfactionRating(@Param("rating") String rating,
                                            @Param("from") Instant from,
                                            @Param("to") Instant to,
                                            Pageable pageable);
}
