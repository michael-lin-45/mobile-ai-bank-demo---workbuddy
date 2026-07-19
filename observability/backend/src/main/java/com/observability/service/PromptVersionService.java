package com.observability.service;

import com.observability.model.PromptVersion;
import com.observability.repository.PromptVersionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 提示词版本服务（T-M P20，设计 §3.4）。
 *
 * <p>职责：
 * <ul>
 *   <li>create：版本号 = 同一 promptKey 已存在最大 version + 1；首个版本自动 ACTIVE，其余 DRAFT；</li>
 *   <li>getActive：返回该 promptKey 当前 ACTIVE（version 最大）的版本；</li>
 *   <li>activate：将目标版本置 ACTIVE，并把同 key 的其它 ACTIVE 归档为 ARCHIVED（状态机）；</li>
 *   <li>list / get / delete：常规 CRUD。</li>
 * </ul>
 */
@Slf4j
@Service
public class PromptVersionService {

    private final PromptVersionRepository repository;

    public PromptVersionService(PromptVersionRepository repository) {
        this.repository = repository;
    }

    /**
     * 创建新版本。版本号单调递增；首个版本自动生效（ACTIVE），其余为草稿（DRAFT）。
     */
    public PromptVersion create(String promptKey, String content, String model) {
        if (promptKey == null || promptKey.isBlank()) {
            throw new IllegalArgumentException("promptKey 不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        int nextVersion = repository.maxVersion(promptKey) + 1;
        boolean hasActive = !repository.findByPromptKeyAndStatusOrderByVersionDesc(promptKey, "ACTIVE").isEmpty();
        String status = hasActive ? "DRAFT" : "ACTIVE";

        PromptVersion pv = PromptVersion.builder()
                .promptKey(promptKey)
                .version(nextVersion)
                .content(content)
                .model(model)
                .status(status)
                .build();
        PromptVersion saved = repository.save(pv);
        log.info("[PromptVersion] created promptKey={} version={} status={}", promptKey, nextVersion, status);
        return saved;
    }

    /** 列出某 promptKey 的全部版本（version 降序）；promptKey 为空时返回全部 */
    public List<PromptVersion> listByPromptKey(String promptKey) {
        if (promptKey == null || promptKey.isBlank()) {
            return repository.findAllByOrderByPromptKeyAscVersionDesc();
        }
        return repository.findByPromptKeyOrderByVersionDesc(promptKey);
    }

    /** 获取单条版本 */
    public PromptVersion get(Long id) {
        return repository.findById(id).orElse(null);
    }

    /** 获取当前 ACTIVE 版本（version 最大者） */
    public PromptVersion getActive(String promptKey) {
        if (promptKey == null || promptKey.isBlank()) return null;
        return repository.findByPromptKeyAndStatusOrderByVersionDesc(promptKey, "ACTIVE")
                .stream().findFirst().orElse(null);
    }

    /**
     * 激活某版本：归档同 key 的其它 ACTIVE（→ARCHIVED），目标版本置 ACTIVE。
     */
    @Transactional
    public PromptVersion activate(Long id) {
        PromptVersion target = repository.findById(id).orElse(null);
        if (target == null) {
            return null;
        }
        // 归档同 promptKey 的其它 ACTIVE 版本（状态机：旧 ACTIVE → ARCHIVED）
        List<PromptVersion> others = repository.findByPromptKeyAndStatusOrderByVersionDesc(
                target.getPromptKey(), "ACTIVE");
        for (PromptVersion pv : others) {
            if (!pv.getId().equals(target.getId())) {
                pv.setStatus("ARCHIVED");
                repository.save(pv);
            }
        }
        target.setStatus("ACTIVE");
        PromptVersion saved = repository.save(target);
        log.info("[PromptVersion] activated promptKey={} version={} id={}",
                target.getPromptKey(), target.getVersion(), target.getId());
        return saved;
    }

    /** 删除版本 */
    public void delete(Long id) {
        repository.deleteById(id);
    }
}
