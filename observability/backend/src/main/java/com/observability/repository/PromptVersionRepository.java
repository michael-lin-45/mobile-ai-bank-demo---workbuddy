package com.observability.repository;

import com.observability.model.PromptVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 提示词版本仓储（T-M P20，设计 §3.4①）。
 */
@Repository
public interface PromptVersionRepository extends JpaRepository<PromptVersion, Long> {

    /** 按 promptKey + version 精确查询（联合唯一约束保证至多一条） */
    Optional<PromptVersion> findByPromptKeyAndVersion(String promptKey, int version);

    /** 按 promptKey 列出全部版本（version 降序） */
    List<PromptVersion> findByPromptKeyOrderByVersionDesc(String promptKey);

    /** 按 promptKey + status 查（如取 ACTIVE 版本，version 降序；activate 时可能归档多条） */
    List<PromptVersion> findByPromptKeyAndStatusOrderByVersionDesc(String promptKey, String status);

    /** 列出全部版本（跨 promptKey，按 promptKey 升序 + version 降序），用于无 promptKey 入参时 */
    List<PromptVersion> findAllByOrderByPromptKeyAscVersionDesc();

    /**
     * 取某 promptKey 已存在的最大版本号（无记录返回 0）。
     * 新版本号 = maxVersion + 1（设计 §3.4②）。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COALESCE(MAX(p.version), 0) FROM PromptVersion p WHERE p.promptKey = :pk")
    int maxVersion(@Param("pk") String promptKey);
}
