package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.model.PromptVersion;
import com.observability.service.PromptVersionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 提示词版本控制器（T-M P20，设计 §3.4）。
 *
 * <p>REST 路径（QA 测试契约假设 PROMPT_VERSION_PATH=/api/v1/ai/prompt-versions）：
 * <ul>
 *   <li>POST   /api/v1/ai/prompt-versions              — 创建版本（version 自动 +1）</li>
 *   <li>GET    /api/v1/ai/prompt-versions?promptKey=   — 列出某 key 全部版本</li>
 *   <li>GET    /api/v1/ai/prompt-versions?promptKey=&status=ACTIVE — 取当前 ACTIVE</li>
 *   <li>GET    /api/v1/ai/prompt-versions/active/{key} — 取当前 ACTIVE（等价约定）</li>
 *   <li>GET    /api/v1/ai/prompt-versions/{id}         — 获取单条</li>
 *   <li>POST   /api/v1/ai/prompt-versions/{id}/activate — 激活（兼容 PUT）</li>
 *   <li>DELETE /api/v1/ai/prompt-versions/{id}         — 删除</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/ai/prompt-versions")
public class PromptVersionController {

    private final PromptVersionService promptVersionService;

    public PromptVersionController(PromptVersionService promptVersionService) {
        this.promptVersionService = promptVersionService;
    }

    /** 创建版本 */
    @PostMapping
    public ApiResponse<PromptVersion> create(@RequestBody Map<String, Object> body) {
        try {
            String promptKey = (String) body.get("promptKey");
            String content = (String) body.get("content");
            String model = (String) body.getOrDefault("model", null);
            PromptVersion created = promptVersionService.create(promptKey, content, model);
            return ApiResponse.ok(created, "Prompt version created");
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to create prompt version: " + e.getMessage());
        }
    }

    /**
     * 列表 / 取 ACTIVE。
     * - status=ACTIVE 且带 promptKey → 返回当前 ACTIVE 单条；
     * - 否则按 promptKey 返回全部版本列表（promptKey 可空）。
     */
    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) String promptKey,
                               @RequestParam(required = false) String status) {
        try {
            if ("ACTIVE".equalsIgnoreCase(status) && promptKey != null && !promptKey.isBlank()) {
                PromptVersion active = promptVersionService.getActive(promptKey);
                return ApiResponse.ok(active);
            }
            List<PromptVersion> list = promptVersionService.listByPromptKey(promptKey);
            return ApiResponse.ok(list);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to list prompt versions: " + e.getMessage());
        }
    }

    /** 取当前 ACTIVE 版本（等价约定 /active/{key}） */
    @GetMapping("/active/{promptKey}")
    public ApiResponse<PromptVersion> getActive(@PathVariable String promptKey) {
        try {
            PromptVersion active = promptVersionService.getActive(promptKey);
            if (active == null) {
                return ApiResponse.error(404, "No ACTIVE prompt version for: " + promptKey);
            }
            return ApiResponse.ok(active);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to get active prompt version: " + e.getMessage());
        }
    }

    /** 获取单条版本 */
    @GetMapping("/{id}")
    public ApiResponse<PromptVersion> get(@PathVariable Long id) {
        try {
            PromptVersion pv = promptVersionService.get(id);
            if (pv == null) {
                return ApiResponse.error(404, "Prompt version not found: " + id);
            }
            return ApiResponse.ok(pv);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to get prompt version: " + e.getMessage());
        }
    }

    /** 激活版本（POST 约定，兼容 PUT） */
    @PostMapping("/{id}/activate")
    public ApiResponse<PromptVersion> activate(@PathVariable Long id) {
        try {
            PromptVersion activated = promptVersionService.activate(id);
            if (activated == null) {
                return ApiResponse.error(404, "Prompt version not found: " + id);
            }
            return ApiResponse.ok(activated, "Prompt version activated");
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to activate prompt version: " + e.getMessage());
        }
    }

    /** 激活版本（PUT 兼容约定） */
    @PutMapping("/{id}/activate")
    public ApiResponse<PromptVersion> activatePut(@PathVariable Long id) {
        return activate(id);
    }

    /** 删除版本 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        try {
            promptVersionService.delete(id);
            return ApiResponse.ok(null, "Prompt version deleted");
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to delete prompt version: " + e.getMessage());
        }
    }
}
