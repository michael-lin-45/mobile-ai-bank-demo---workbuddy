package com.mobileagent.app.memory.impl;

import com.alibaba.cloud.ai.graph.store.NamespaceListRequest;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.alibaba.cloud.ai.graph.store.StoreSearchRequest;
import com.alibaba.cloud.ai.graph.store.StoreSearchResult;
import com.alibaba.cloud.ai.graph.store.stores.BaseStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 真正的 Redis Store 实现 — 用 StringRedisTemplate 持久化 StoreItem
 *
 * 为什么不用框架的 RedisStore?
 * 框架的 RedisStore 内部用 HashMap + ReentrantReadWriteLock 模拟 Redis 行为,
 * 不是真正的 Redis 持久化, 重启后数据丢失, 无法用于生产环境.
 *
 * 存储结构:
 * - 数据: key=memory-store:{namespace1}:{namespace2}:...:{itemKey}, value=JSON(StoreItem)
 * - 索引: Redis Set "memory-store:__all_keys__" 跟踪所有数据 key (用于 search/size/clear)
 * - 命名空间: Redis Set "memory-store:__namespaces__" 跟踪所有命名空间路径 (用于 listNamespaces)
 *
 * 继承 BaseStore 以复用:
 * - validatePutItem / validateGetItem / validateDeleteItem / validateSearchItems / validateListNamespaces
 * - matchesSearchCriteria (搜索过滤)
 * - createComparator (排序)
 * - startsWithPrefix (命名空间前缀匹配)
 */
@Slf4j
public class RedisStoreAdapter extends BaseStore {

    private static final String KEY_PREFIX = "memory-store:";
    private static final String ALL_KEYS_SET = "memory-store:__all_keys__";
    private static final String NAMESPACES_SET = "memory-store:__namespaces__";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisStoreAdapter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // ==================== 核心读写 ====================

    @Override
    public void putItem(StoreItem item) {
        validatePutItem(item);
        try {
            String redisKey = buildRedisKey(item.getNamespace(), item.getKey());
            String json = objectMapper.writeValueAsString(serializeStoreItem(item));

            redisTemplate.opsForValue().set(redisKey, json);
            redisTemplate.opsForSet().add(ALL_KEYS_SET, redisKey);

            // 记录命名空间路径
            String namespacePath = String.join(":", item.getNamespace());
            redisTemplate.opsForSet().add(NAMESPACES_SET, namespacePath);

            log.trace("[RedisStoreAdapter] putItem: namespace={}, key={}", item.getNamespace(), item.getKey());
        } catch (Exception e) {
            throw new RuntimeException("Failed to store item in Redis", e);
        }
    }

    @Override
    public Optional<StoreItem> getItem(List<String> namespace, String key) {
        validateGetItem(namespace, key);
        try {
            String redisKey = buildRedisKey(namespace, key);
            String json = redisTemplate.opsForValue().get(redisKey);
            if (json == null) return Optional.empty();

            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {});
            return Optional.of(deserializeStoreItem(data));
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve item from Redis", e);
        }
    }

    @Override
    public boolean deleteItem(List<String> namespace, String key) {
        validateDeleteItem(namespace, key);
        try {
            String redisKey = buildRedisKey(namespace, key);
            Boolean deleted = redisTemplate.delete(redisKey);
            if (Boolean.TRUE.equals(deleted)) {
                redisTemplate.opsForSet().remove(ALL_KEYS_SET, redisKey);
            }
            return Boolean.TRUE.equals(deleted);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete item from Redis", e);
        }
    }

    // ==================== 搜索 ====================

    @Override
    public StoreSearchResult searchItems(StoreSearchRequest searchRequest) {
        validateSearchItems(searchRequest);
        List<StoreItem> allItems = getAllItems();

        // 过滤
        List<StoreItem> filteredItems = allItems.stream()
                .filter(item -> matchesSearchCriteria(item, searchRequest))
                .collect(Collectors.toCollection(ArrayList::new));

        long totalCount = filteredItems.size();

        // 排序
        if (searchRequest.getSortFields() != null && !searchRequest.getSortFields().isEmpty()) {
            filteredItems.sort(createComparator(searchRequest));
        }

        // 分页
        int offset = searchRequest.getOffset();
        int limit = searchRequest.getLimit();
        if (limit <= 0) limit = 10;
        int endIndex = Math.min(offset + limit, filteredItems.size());

        if (offset >= filteredItems.size()) {
            return StoreSearchResult.of(Collections.emptyList(), totalCount, offset, limit);
        }

        List<StoreItem> resultItems = filteredItems.subList(offset, endIndex);
        return StoreSearchResult.of(resultItems, totalCount, offset, limit);
    }

    // ==================== 命名空间 ====================

    @Override
    public List<String> listNamespaces(NamespaceListRequest namespaceRequest) {
        validateListNamespaces(namespaceRequest);

        Set<String> allNamespacePaths = redisTemplate.opsForSet().members(NAMESPACES_SET);
        if (allNamespacePaths == null || allNamespacePaths.isEmpty()) {
            return Collections.emptyList();
        }

        // 前缀过滤 + 深度限制
        Set<String> result = new HashSet<>();
        for (String path : allNamespacePaths) {
            List<String> parts = List.of(path.split(":"));

            // 前缀匹配 (namespace 过滤)
            if (namespaceRequest.getNamespace() != null && !namespaceRequest.getNamespace().isEmpty()) {
                if (!startsWithPrefix(parts, namespaceRequest.getNamespace())) {
                    continue;
                }
            }

            // 深度限制
            int maxDepth = namespaceRequest.getMaxDepth();

            if (maxDepth > 0 && parts.size() > maxDepth) {
                path = String.join(":", parts.subList(0, maxDepth));
            }
            result.add(path);
        }

        return new ArrayList<>(result);
    }

    // ==================== 管理操作 ====================

    @Override
    public void clear() {
        Set<String> allKeys = redisTemplate.opsForSet().members(ALL_KEYS_SET);
        if (allKeys != null && !allKeys.isEmpty()) {
            redisTemplate.delete(allKeys);
        }
        redisTemplate.delete(ALL_KEYS_SET);
        redisTemplate.delete(NAMESPACES_SET);
    }

    @Override
    public long size() {
        Long count = redisTemplate.opsForSet().size(ALL_KEYS_SET);
        return count != null ? count : 0;
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    // ==================== 内部方法 ====================

    /** 构建 Redis key: memory-store:namespace1:namespace2:...:itemKey */
    private String buildRedisKey(List<String> namespace, String key) {
        String namespacePart = String.join(":", namespace);
        return KEY_PREFIX + namespacePart + ":" + key;
    }

    /** 获取所有 StoreItem (用于 search/listNamespaces) */
    private List<StoreItem> getAllItems() {
        Set<String> allKeys = redisTemplate.opsForSet().members(ALL_KEYS_SET);
        if (allKeys == null || allKeys.isEmpty()) {
            return Collections.emptyList();
        }

        List<StoreItem> items = new ArrayList<>();
        List<String> jsonValues = redisTemplate.opsForValue().multiGet(allKeys);
        if (jsonValues == null) return items;

        for (String json : jsonValues) {
            if (json == null) continue;
            try {
                Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {});
                items.add(deserializeStoreItem(data));
            } catch (Exception e) {
                log.warn("[RedisStoreAdapter] Failed to deserialize item, skipping", e);
            }
        }
        return items;
    }

    /** 序列化 StoreItem → Map (包含元数据) */
    private Map<String, Object> serializeStoreItem(StoreItem item) {
        return Map.of(
                "namespace", item.getNamespace(),
                "key", item.getKey(),
                "value", item.getValue() != null ? item.getValue() : Map.of(),
                "createdAt", item.getCreatedAt(),
                "updatedAt", item.getUpdatedAt()
        );
    }

    /** 反序列化 Map → StoreItem */
    @SuppressWarnings("unchecked")
    private StoreItem deserializeStoreItem(Map<String, Object> data) {
        List<String> namespace = (List<String>) data.get("namespace");
        String key = (String) data.get("key");
        Map<String, Object> value = (Map<String, Object>) data.getOrDefault("value", Map.of());

        StoreItem item = StoreItem.of(namespace, key, value);

        // 恢复时间戳
        if (data.get("createdAt") instanceof Number createdAt) {
            item.setCreatedAt(createdAt.longValue());
        }
        if (data.get("updatedAt") instanceof Number updatedAt) {
            item.setUpdatedAt(updatedAt.longValue());
        }

        return item;
    }
}
