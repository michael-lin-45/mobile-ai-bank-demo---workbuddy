package com.mobileagent.app.memory;

/**
 * GlobalSessionStateStore 的存储后端接口
 *
 * 通过 storage.type 配置切换实现:
 * - in-memory: ConcurrentHashMap (默认, 单实例开发)
 * - redis:     StringRedisTemplate (多实例/持久化)
 */
public interface GlobalSessionRepository {

    /** 获取 GlobalSessionContext, 不存在返回 null */
    GlobalSessionContext get(String sessionId);

    /** 保存 GlobalSessionContext */
    void put(String sessionId, GlobalSessionContext ctx);

    /** 删除 GlobalSessionContext */
    void remove(String sessionId);
}
