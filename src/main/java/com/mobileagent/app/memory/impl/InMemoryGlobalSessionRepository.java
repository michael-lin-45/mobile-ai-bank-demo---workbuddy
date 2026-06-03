package com.mobileagent.app.memory.impl;

import com.mobileagent.app.memory.GlobalSessionContext;
import com.mobileagent.app.memory.GlobalSessionRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemory 实现的 GlobalSessionRepository — 基于 ConcurrentHashMap
 */
@Slf4j
public class InMemoryGlobalSessionRepository implements GlobalSessionRepository {

    private final Map<String, GlobalSessionContext> store = new ConcurrentHashMap<>();

    @Override
    public GlobalSessionContext get(String sessionId) {
        return store.get(sessionId);
    }

    @Override
    public void put(String sessionId, GlobalSessionContext ctx) {
        store.put(sessionId, ctx);
    }

    @Override
    public void remove(String sessionId) {
        store.remove(sessionId);
    }
}
