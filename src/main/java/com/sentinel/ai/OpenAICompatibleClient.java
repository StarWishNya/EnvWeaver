package com.sentinel.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentinel.config.properties.AIProperties;
import okhttp3.OkHttpClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI Chat Completions 兼容客户端。
 * POST {base-url}/chat/completions，Authorization: Bearer。
 */
public final class OpenAICompatibleClient implements AIProviderClient {

    private final String name;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final int maxTokens;
    private final double temperature;
    private final String thinking;
    private final OkHttpClient httpClient;

    public OpenAICompatibleClient(String name, AIProperties.Provider config) {
        this.name = name;
        this.model = config.getModel() == null ? "" : config.getModel();
        this.baseUrl = config.getBaseUrl();
        this.apiKey = config.getApiKey();
        this.maxTokens = AIHttp.maxTokens(config.getMaxTokens());
        this.temperature = AIHttp.temperature(config.getTemperature());
        this.thinking = config.getThinking();
        this.httpClient = AIHttp.newClient(config.getTimeout());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public AIProtocol protocol() {
        return AIProtocol.OPENAI;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean isConfigured() {
        return AIHttp.hasText(baseUrl) && AIHttp.hasText(apiKey);
    }

    @Override
    public AIResponse complete(AIRequest request) {
        try {
            String url = AIHttp.endpoint(baseUrl, "/chat/completions");
            String body = AIHttp.post(httpClient, name, apiKey, url, headers(), requestJson(request));
            return new AIResponse(name, model, parseContent(body));
        } catch (AIProviderException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new AIProviderException(name + " 请求失败: " + AIHttp.snippet(ex.getMessage(), apiKey), ex);
        }
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (AIHttp.hasText(apiKey)) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return headers;
    }

    private String requestJson(AIRequest request) {
        ObjectNode root = AIHttp.mapper().createObjectNode();
        root.put("model", model);
        ArrayNode messages = root.putArray("messages");
        if (request != null && request.systemPrompt() != null) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        String userPrompt = request == null || request.userPrompt() == null ? "" : request.userPrompt();
        messages.addObject().put("role", "user").put("content", userPrompt);
        root.put("temperature", temperature);
        root.put("max_tokens", maxTokens);
        if (request != null && request.jsonOutput()) {
            root.putObject("response_format").put("type", "json_object");
        }
        AIHttp.applyThinking(root, AIProtocol.OPENAI, thinking, maxTokens);
        try {
            return AIHttp.mapper().writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new AIProviderException(name + " 请求序列化失败", ex);
        }
    }

    private String parseContent(String body) {
        JsonNode root = AIHttp.readTree(body, name, apiKey);
        JsonNode choices = root.get("choices");
        if (choices == null || choices.isNull() || !choices.isArray() || choices.isEmpty()) {
            throw new AIProviderException(name + " 响应缺少 choices");
        }
        JsonNode first = choices.get(0);
        if (first == null || first.isNull()) {
            throw new AIProviderException(name + " 响应缺少 choices");
        }
        JsonNode message = first.get("message");
        if (message == null || message.isNull()) {
            throw new AIProviderException(name + " 响应缺少 message");
        }
        JsonNode content = message.get("content");
        if (content == null || content.isNull() || content.isMissingNode()) {
            throw new AIProviderException(name + " 响应缺少 content");
        }
        if (!(content.isTextual() || content.isNumber() || content.isBoolean())) {
            throw new AIProviderException(name + " 响应缺少 content");
        }
        String text = content.asText();
        if (text.isBlank()) {
            throw new AIProviderException(AIHttp.blankOutputMessage(name, "finish_reason",
                    AIHttp.fieldText(first, "finish_reason")));
        }
        return text;
    }
}
