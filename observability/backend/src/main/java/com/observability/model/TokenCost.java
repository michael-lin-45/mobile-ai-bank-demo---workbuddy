package com.observability.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA Entity — Token成本明细记录
 * 对应 H2 表 token_cost
 */
@Entity
@Table(name = "token_cost", indexes = {
    @Index(name = "idx_tc_intent", columnList = "intent, timestamp")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenCost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64)
    private String intent;

    @Column(length = 64)
    private String model;

    @Column(name = "call_count")
    private Integer callCount;

    @Column(name = "tokens_in")
    private Long tokensIn;

    @Column(name = "tokens_out")
    private Long tokensOut;

    /** 聚合窗口: 1m / 5m / 15m / 1h */
    @Column(name = "agg_window", length = 16)
    private String aggWindow;

    @Column(name = "\"timestamp\"", nullable = false)
    private Instant timestamp;
}
