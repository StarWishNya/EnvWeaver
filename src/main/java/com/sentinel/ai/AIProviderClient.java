package com.sentinel.ai;

/**
 * 单个已命名的 AI 提供商客户端。
 */
public interface AIProviderClient {

    /**
     * 配置中的提供商名称，例如 deepseek。
     */
    String name();

    /**
     * 该提供商使用的协议。
     */
    AIProtocol protocol();

    /**
     * 配置的模型名。
     */
    String model();

    /**
     * OLLAMA：base-url 已设置。
     * 其他协议：base-url 与 api-key 都已设置。
     */
    boolean isConfigured();

    /**
     * 发起一次补全。任何失败都抛出 {@link AIProviderException}，且异常信息不得包含 API Key。
     */
    AIResponse complete(AIRequest request);
}
