package com.mobileagent.app.memory;

import com.alibaba.cloud.ai.graph.store.NamespaceListRequest;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.alibaba.cloud.ai.graph.store.StoreSearchRequest;
import com.alibaba.cloud.ai.graph.store.StoreSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 长期记忆存储门面 — 封装框架的 Store 接口, 提供便捷方法 + 框架集成入口
 *
 * 设计:
 * - 内部持有框架 Store 实例, 后端通过 memory.store.type 配置切换 (InMemory / Redis)
 * - 暴露 getStore() 供 RunnableConfig.builder().store() 使用
 * - 提供便捷方法简化常见操作, 不暴露 Store 的全部 API 细节
 *
 * 定位 (与 GlobalSessionRepository 的区别):
 * - GlobalSessionRepository: "会话寄存器" — 存 GlobalSessionContext, sessionId 维度, 单会话, Java 代码消费
 * - GlobalMemoryStore: "长期记忆" — 存 StoreItem (namespace+key+value), userId 维度, 跨会话, LLM 消费 (Tool/Hook)
 *
 * 使用方式:
 * // 框架集成 — 传入 RunnableConfig
 * RunnableConfig config = RunnableConfig.builder()
 *     .threadId("...")
 *     .store(globalMemoryStore.getStore())
 *     .build();
 *
 * // 便捷方法 — 业务代码直接使用
 * globalMemoryStore.putItem(List.of("users", userId, "preferences"), "risk_profile",
 *     Map.of("level", "稳健", "source", "用户自述"));
 * Optional<StoreItem> profile = globalMemoryStore.getItem(
 *     List.of("users", userId, "preferences"), "risk_profile");
 */
@Slf4j
@Component
public class GlobalMemoryStore {

    private final Store store;

    public GlobalMemoryStore(Store store) {
        this.store = store;
        log.info("[GlobalMemoryStore] Initialized, store={}", store.getClass().getSimpleName());
    }

    // ==================== 框架集成 ====================

    /** 获取底层 Store 实例, 供 RunnableConfig.builder().store() 使用 */
    public Store getStore() {
        return store;
    }

    // ==================== 便捷方法 ====================

    /** 读取 StoreItem */
    public Optional<StoreItem> getItem(List<String> namespace, String key) {
        return store.getItem(namespace, key);
    }

    /** 写入 StoreItem */
    public void putItem(StoreItem item) {
        store.putItem(item);
        log.debug("[GlobalMemoryStore] putItem: namespace={}, key={}", item.getNamespace(), item.getKey());
    }

    /** 便捷写入 — 自动构建 StoreItem */
    public void putItem(List<String> namespace, String key, Map<String, Object> value) {
        putItem(StoreItem.of(namespace, key, value));
    }

    /** 删除 StoreItem */
    public boolean deleteItem(List<String> namespace, String key) {
        boolean deleted = store.deleteItem(namespace, key);
        if (deleted) {
            log.debug("[GlobalMemoryStore] deleteItem: namespace={}, key={}", namespace, key);
        }
        return deleted;
    }

    /** 搜索 StoreItem (支持过滤、排序、分页) */
    public StoreSearchResult searchItems(StoreSearchRequest request) {
        return store.searchItems(request);
    }

    /** 列出命名空间 (支持前缀过滤、最大深度) */
    public List<String> listNamespaces(NamespaceListRequest request) {
        return store.listNamespaces(request);
    }

    /** 清空所有 StoreItem */
    public void clear() {
        store.clear();
        log.info("[GlobalMemoryStore] Cleared all items");
    }

    /** StoreItem 总数 */
    public long size() {
        return store.size();
    }
}
