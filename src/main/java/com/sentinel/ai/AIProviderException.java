package com.sentinel.ai;

/**
 * AI 提供商调用失败。
 * message 与 toString 不得包含 API Key。
 */
public class AIProviderException extends RuntimeException {

    public AIProviderException(String message) {
        super(message);
    }

    public AIProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
