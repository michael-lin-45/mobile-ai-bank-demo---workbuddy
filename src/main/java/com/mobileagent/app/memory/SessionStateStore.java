package com.mobileagent.app.memory;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 会话状态存储接口 — 抽象业务级状态存储 (activeAgents, suspendedAgents等)
 *
 * 改 storage.type=redis 重启即可从内存切换到Redis。
 * 不同命名空间(namespace)隔离不同用途的状态，如:
 * - "active-agents" → ActiveAgentInfo
 * - "suspended-agents" → 嵌套Map
 * - "disambiguation" → DisambiguationState
 * - "last-domains" → LastDomainEntry
 *
 * @param <V> 状态值类型
 */
public interface SessionStateStore<V> {

    /**
     * 获取状态
     *
     * @param namespace 命名空间(如"active-agents:transfer")
     * @param key 键(如sessionId)
     * @return 状态值，不存在返回Optional.empty()
     */
    Optional<V> get(String namespace, String key);

    /**
     * 保存状态
     *
     * @param namespace 命名空间
     * @param key 键
     * @param value 状态值
     */
    void put(String namespace, String key, V value);

    /**
     * 删除状态
     *
     * @param namespace 命名空间
     * @param key 键
     */
    void remove(String namespace, String key);

    /**
     * 检查key是否存在
     *
     * @param namespace 命名空间
     * @param key 键
     */
    boolean containsKey(String namespace, String key);

    /**
     * 获取命名空间下所有entry
     *
     * @param namespace 命名空间
     * @return 所有key-value对
     */
    Map<String, V> getAll(String namespace);

    /**
     * 获取命名空间下所有entry并过滤过期的
     *
     * @param namespace 命名空间
     * @param isExpired 过期判断函数
     */
    default Map<String, V> getAllAndCleanExpired(String namespace, java.util.function.Predicate<V> isExpired) {
        Map<String, V> all = getAll(namespace);
        var iter = all.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            if (isExpired.test(entry.getValue())) {
                remove(namespace, entry.getKey());
                iter.remove();
            }
        }
        return all;
    }

    /**
     * 删除命名空间下所有entry
     *
     * @param namespace 命名空间
     */
    void clear(String namespace);

    /**
     * 清理命名空间下过期的entry
     *
     * @param namespace 命名空间
     * @param isExpired 过期判断函数
     */
    default void cleanExpired(String namespace, java.util.function.Predicate<V> isExpired) {
        getAllAndCleanExpired(namespace, isExpired);
    }
}
