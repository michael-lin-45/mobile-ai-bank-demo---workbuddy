package com.observability.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA Entity — 提示词版本（T-M P20 版本化，设计 §3.4①）。
 *
 * <p>同一 {@code promptKey} 下版本号单调递增（version = 已存在最大 version + 1），
 * 联合唯一约束 UNIQUE(prompt_key, version)。status 取值：DRAFT / ACTIVE / ARCHIVED。
 * 同一 promptKey 同时至多一个 ACTIVE；activate 时旧 ACTIVE 归档为 ARCHIVED。
 */
@Entity
@Table(name = "prompt_versions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_pv_key_version", columnNames = {"prompt_key", "version"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromptVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prompt_key", nullable = false, length = 64)
    private String promptKey;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "content", columnDefinition = "CLOB", nullable = false)
    private String content;

    @Column(name = "model", length = 64)
    private String model;

    /** DRAFT / ACTIVE / ARCHIVED */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "description", length = 256)
    private String description;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null || status.isBlank()) {
            status = "DRAFT";
        }
    }
}
