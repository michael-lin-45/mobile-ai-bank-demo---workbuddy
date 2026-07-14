package com.mobileagent.app.router.registry;

import com.mobileagent.app.domain.AbstractDomainService;
import com.mobileagent.app.domain.DomainHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 领域服务注册表 - 替代BankController中的switch-case
 *
 * 支持:
 * - 按领域名查找DomainHandler
 * - 按意图名反查所属领域(REROUTE时使用)
 * - 查询所有已注册领域名
 */
@Slf4j
@Component
public class DomainServiceRegistry {

    private final Map<String, DomainHandler> registry = new LinkedHashMap<>();

    public void register(String domain, DomainHandler handler) {
        registry.put(domain, handler);
        log.info("[DomainServiceRegistry] Registered domain: {} → {}", domain, handler.getClass().getSimpleName());
    }

    public DomainHandler getHandler(String domain) {
        return registry.get(domain);
    }

    public Set<String> getDomainNames() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    /**
     * 获取所有已注册的 DomainHandler（用于L1上下文扫描等场景）
     */
    public Collection<DomainHandler> getAllHandlers() {
        return Collections.unmodifiableCollection(registry.values());
    }

    /**
     * 根据意图名反查所属领域
     *
     * 遍历所有注册的DomainHandler，查找哪个handler的handledIntents包含该意图。
     * 对于ChatService等非AbstractDomainService的handler，返回null。
     *
     * @param intentName 意图名(如"TRANSFER")
     * @return 领域名(如"TRANSFER")，未找到返回null
     */
    public String findDomainForIntent(String intentName) {
        for (Map.Entry<String, DomainHandler> entry : registry.entrySet()) {
            DomainHandler handler = entry.getValue();
            if (handler instanceof AbstractDomainService ads) {
                for (AbstractDomainService.IntentInfo info : ads.getHandledIntents()) {
                    if (info.getIntentName().equals(intentName)) {
                        return entry.getKey();
                    }
                }
            }
        }
        return null;
    }
}
