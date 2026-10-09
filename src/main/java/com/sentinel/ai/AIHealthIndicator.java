package com.sentinel.ai;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Actuator 健康项，名称是 ai。
 * 只暴露各提供商是否已配置、模型与协议，不包含密钥，也不发起网络请求。
 * 未配置时返回 UNKNOWN，避免把整体健康状态打成 DOWN。
 */
@Component("ai")
public class AIHealthIndicator implements HealthIndicator {

    private final AIRouter aiRouter;

    public AIHealthIndicator(AIRouter aiRouter) {
        this.aiRouter = aiRouter;
    }

    @Override
    public Health health() {
        List<AIProviderStatus> statuses = aiRouter.status();
        boolean anyConfigured = false;
        List<Map<String, Object>> providers = new ArrayList<>();
        for (AIProviderStatus status : statuses) {
            if (status.configured()) {
                anyConfigured = true;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", status.name());
            row.put("protocol", status.protocol() == null ? "" : status.protocol().name());
            row.put("model", status.model() == null ? "" : status.model());
            row.put("configured", status.configured());
            providers.add(row);
        }
        Health.Builder builder = anyConfigured ? Health.up() : Health.unknown();
        return builder.withDetail("providers", providers).build();
    }
}
