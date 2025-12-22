package com.sentinel.config;

import com.sentinel.config.properties.AIProperties;
import com.sentinel.service.AIClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * AI 客户端工厂
 * 根据配置返回对应的 AI 客户端实现
 * 
 * @author Sentinel Team
 */
@Slf4j
@Component
public class AIClientFactory {

    /**
     * AI 提供商类型常量
     */
    private static final String PROVIDER_OLLAMA = "ollama";
    private static final String PROVIDER_OPENAI = "openai";
    private static final String PROVIDER_CUSTOM = "custom";

    /**
     * Spring Bean 名称常量
     */
    private static final String BEAN_OLLAMA_CLIENT = "ollamaClient";
    private static final String BEAN_OPENAI_CLIENT = "openaiClient";

    private final ApplicationContext applicationContext;
    private final AIProperties aiProperties;

    public AIClientFactory(ApplicationContext applicationContext, AIProperties aiProperties) {
        this.applicationContext = applicationContext;
        this.aiProperties = aiProperties;
    }

    /**
     * 获取 AI 客户端
     * 
     * @return AI 客户端实例
     */
    public AIClient getAIClient() {
        String provider = aiProperties.getProvider();
        
        log.info("使用 AI 提供商: {}", provider);
        
        switch (provider.toLowerCase()) {
            case PROVIDER_OLLAMA:
                return applicationContext.getBean(BEAN_OLLAMA_CLIENT, AIClient.class);
            case PROVIDER_OPENAI:
                return applicationContext.getBean(BEAN_OPENAI_CLIENT, AIClient.class);
            case PROVIDER_CUSTOM:
                // 自定义 AI 提供商使用 OpenAI 兼容接口
                return applicationContext.getBean(BEAN_OPENAI_CLIENT, AIClient.class);
            default:
                log.warn("未知的 AI 提供商: {}, 使用默认的 Ollama", provider);
                return applicationContext.getBean(BEAN_OLLAMA_CLIENT, AIClient.class);
        }
    }
}
