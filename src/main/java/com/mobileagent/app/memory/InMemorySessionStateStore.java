package com.mobileagent.app.memory;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemory实现的SessionStateStore — 基于 ConcurrentHashMap
 *
 * 与Redis实现完全等价，改 storage.type=in-memory 重启即可切换
 */
@Slf4j
public class InMemorySessionStateStore<V> implements SessionStateStore<V> {

    /** namespace → (key → value) */
    private final Map<String, Map<String, V>> stores = new ConcurrentHashMap<>();

    @Override
    public Optional<V> get(String namespace, String key) {
        Map<String, V> store = stores.get(namespace);
        if (store == null) return Optional.empty();
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void put(String namespace, String key, V value) {
        stores.computeIfAbsent(namespace, k -> new ConcurrentHashMap<>()).put(key, value);
    }

    @Override
    public void remove(String namespace, String key) {
        Map<String, V> store = stores.get(namespace);
        if (store != null) {
            store.remove(key);
        }
    }

    @Override
    public boolean containsKey(String namespace, String key) {
        Map<String, V> store = stores.get(namespace);
        return store != null && store.containsKey(key);
    }

    @Override
    public Map<String, V> getAll(String namespace) {
        Map<String, V> store = stores.get(namespace);
        if (store == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(new HashMap<>(store));
    }

    @Override
    public void clear(String namespace) {
        stores.remove(namespace);
    }
}
