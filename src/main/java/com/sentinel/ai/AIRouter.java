package com.sentinel.ai;

import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.AIProperties;
import com.sentinel.enums.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按 ai.provider-order 依次调用已配置的提供商。
 * 未配置或已禁用的项跳过；调用失败记录 WARN 后尝试下一项。
 * 未知名称只在启动时警告一次。
 */
@Slf4j
@Service
public class AIRouter {

    private static final int REASON_LIMIT = 160;

    private final List<AIProviderClient> ordered;
    private final List<AIProviderClient> allClients;
    private final Map<String, AIProperties.Provider> providers;
    private final String providerOrder;

    public AIRouter(AIProperties properties, AIProviderClientFactory factory) {
        this.providers = properties.getProviders() == null ? Map.of() : properties.getProviders();
        this.providerOrder = properties.getProviderOrder() == null ? "" : properties.getProviderOrder();
        Map<String, AIProviderClient> byName = factory.clientsByName();
        List<AIProviderClient> resolved = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        for (String name : properties.orderedProviderNames()) {
            AIProviderClient client = byName.get(name);
            if (client == null) {
                if (unknown.add(name)) {
                    log.warn("未知的 AI 提供商 '{}'，已从调用顺序中跳过", name);
                }
                continue;
            }
            if (seen.add(name)) {
                resolved.add(client);
            }
        }
        this.ordered = List.copyOf(resolved);
        List<AIProviderClient> everyone = new ArrayList<>(resolved);
        for (AIProviderClient client : byName.values()) {
            if (seen.add(client.name())) {
                everyone.add(client);
            }
        }
        this.allClients = List.copyOf(everyone);
        log.info("AI 调用顺序: {}", ordered.stream().map(AIProviderClient::name).toList());
    }

    /**
     * 按顺序尝试提供商。全部失败或没有可调用项时抛出业务异常，错误码 4003。
     */
    public AIResponse complete(AIRequest request) {
        List<String> failures = new ArrayList<>();
        boolean attempted = false;
        for (AIProviderClient client : ordered) {
            if (!isEnabled(client) || !client.isConfigured()) {
                log.debug("跳过未配置或已禁用的 AI 提供商: {}", client.name());
                continue;
            }
            attempted = true;
            try {
                return client.complete(request);
            } catch (AIProviderException ex) {
                String reason = shortReason(ex);
                log.warn("AI 提供商 {} 调用失败: {}", client.name(), reason);
                failures.add(client.name() + ": " + reason);
            }
        }
        if (!attempted) {
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "AI 服务不可用，无已配置的提供商（provider-order: " + providerOrder + "）");
        }
        throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                "AI 服务不可用，已尝试: " + String.join("; ", failures));
    }

    /**
     * 廉价状态：只看配置，不发起网络请求。
     * available 表示已启用且 isConfigured，不是探活结果。
     */
    public List<AIProviderStatus> status() {
        List<AIProviderStatus> statuses = new ArrayList<>();
        for (AIProviderClient client : allClients) {
            boolean configured = client.isConfigured();
            boolean enabled = isEnabled(client);
            String detail;
            if (!enabled) {
                detail = "disabled";
            } else if (!configured) {
                detail = "not configured";
            } else {
                detail = "configured";
            }
            statuses.add(new AIProviderStatus(
                    client.name(),
                    client.protocol(),
                    client.model() == null ? "" : client.model(),
                    configured,
                    enabled && configured,
                    detail));
        }
        return List.copyOf(statuses);
    }

    private boolean isEnabled(AIProviderClient client) {
        AIProperties.Provider provider = providers.get(client.name());
        if (provider == null || provider.getEnabled() == null) {
            return true;
        }
        return provider.getEnabled();
    }

    private static String shortReason(AIProviderException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        message = message.replace('\r', ' ').replace('\n', ' ').trim();
        if (message.length() <= REASON_LIMIT) {
            return message;
        }
        return message.substring(0, REASON_LIMIT);
    }
}
