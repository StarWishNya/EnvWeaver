package com.sentinel.ai;

import com.sentinel.config.properties.AIProperties;

import java.util.Map;

/**
 * 测试里组装提供商配置与路由。
 */
final class AiClients {

    static final String API_KEY = "sk-test-sentinel-secret-key";

    private AiClients() {
    }

    static AIProperties.Provider provider(AIProtocol protocol, String baseUrl, String apiKey, String model,
                                          int timeout, int maxTokens, double temperature) {
        AIProperties.Provider provider = new AIProperties.Provider();
        provider.setProtocol(protocol);
        provider.setBaseUrl(baseUrl);
        provider.setApiKey(apiKey);
        provider.setModel(model);
        provider.setTimeout(timeout);
        provider.setMaxTokens(maxTokens);
        provider.setTemperature(temperature);
        provider.setEnabled(true);
        return provider;
    }

    static AIRouter router(String order, Map<String, AIProperties.Provider> providers) {
        AIProperties properties = new AIProperties();
        properties.setProviderOrder(order);
        properties.setProviders(providers);
        return new AIRouter(properties, new AIProviderClientFactory(properties));
    }
}
