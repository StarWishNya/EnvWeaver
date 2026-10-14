package com.sentinel.ai;

/**
 * 一次补全请求。systemPrompt 可以为空，userPrompt 为用户内容。
 * jsonOutput 为 true 时，OpenAI 协议附带 response_format=json_object，Ollama 附带 format=json。
 * Anthropic 没有 JSON 模式，不附加字段。
 */
public record AIRequest(String systemPrompt, String userPrompt, boolean jsonOutput) {

    public AIRequest(String systemPrompt, String userPrompt) {
        this(systemPrompt, userPrompt, false);
    }
}
