package com.sentinel.ai;

import com.sentinel.config.properties.AIProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按 ai.providers 为每个提供商创建一个客户端。
 */
@Slf4j
@Component
public class AIProviderClientFactory {

    private final Map<String, AIProviderClient> clientsByName;

    public AIProviderClientFactory(AIProperties properties) {
        Map<String, AIProviderClient> built = new LinkedHashMap<>();
        Map<String, AIProperties.Provider> providers = properties.getProviders();
        if (providers != null) {
            for (Map.Entry<String, AIProperties.Provider> entry : providers.entrySet()) {
                AIProviderClient client = create(entry.getKey(), entry.getValue());
                if (client != null) {
                    built.put(entry.getKey(), client);
                }
            }
        }
        this.clientsByName = Collections.unmodifiableMap(built);
        log.info("已注册 AI 提供商: {}", clientsByName.keySet());
    }

    public Map<String, AIProviderClient> clientsByName() {
        return clientsByName;
    }

    private AIProviderClient create(String name, AIProperties.Provider config) {
        if (config == null || config.getProtocol() == null) {
            log.warn("AI 提供商 '{}' 缺少 protocol，已跳过", name);
            return null;
        }
        return switch (config.getProtocol()) {
            case OPENAI -> new OpenAICompatibleClient(name, config);
            case ANTHROPIC -> new AnthropicClient(name, config);
            case OLLAMA -> new OllamaClient(name, config);
        };
    }
}
