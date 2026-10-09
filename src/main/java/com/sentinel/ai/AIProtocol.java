package com.sentinel.ai;

/**
 * AI 提供商协议。
 * 决定请求路径、鉴权头和响应解析方式。
 */
public enum AIProtocol {

    /** OpenAI Chat Completions 兼容协议 */
    OPENAI,

    /** Anthropic Messages 兼容协议 */
    ANTHROPIC,

    /** Ollama /api/chat 协议 */
    OLLAMA
}
