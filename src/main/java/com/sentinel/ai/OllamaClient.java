package com.sentinel.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentinel.config.properties.AIProperties;
import okhttp3.OkHttpClient;

import java.util.Map;

/**
 * Ollama Chat 客户端。
 * POST {base-url}/api/chat，stream=false，options.temperature 与 options.num_predict。
 */
public final class OllamaClient implements AIProviderClient {

    private final String name;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final int maxTokens;
    private final double temperature;
    private final String thinking;
    private final OkHttpClient httpClient;

    public OllamaClient(String name, AIProperties.Provider config) {
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
        return AIProtocol.OLLAMA;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean isConfigured() {
        return AIHttp.hasText(baseUrl);
    }

    @Override
    public AIResponse complete(AIRequest request) {
        try {
            String url = AIHttp.endpoint(baseUrl, "/api/chat");
            String body = AIHttp.post(httpClient, name, apiKey, url, Map.of(), requestJson(request));
            return new AIResponse(name, model, parseContent(body));
        } catch (AIProviderException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new AIProviderException(name + " 请求失败: " + AIHttp.snippet(ex.getMessage(), apiKey), ex);
        }
    }

    private String requestJson(AIRequest request) {
        ObjectNode root = AIHttp.mapper().createObjectNode();
        root.put("model", model);
        root.put("stream", false);
        if (request != null && request.jsonOutput()) {
            root.put("format", "json");
        }
        ArrayNode messages = root.putArray("messages");
        if (request != null && request.systemPrompt() != null) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        String userPrompt = request == null || request.userPrompt() == null ? "" : request.userPrompt();
        messages.addObject().put("role", "user").put("content", userPrompt);
        ObjectNode options = root.putObject("options");
        options.put("temperature", temperature);
        options.put("num_predict", maxTokens);
        AIHttp.applyThinking(root, AIProtocol.OLLAMA, thinking, maxTokens);
        try {
            return AIHttp.mapper().writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new AIProviderException(name + " 请求序列化失败", ex);
        }
    }

    private String parseContent(String body) {
        JsonNode root = AIHttp.readTree(body, name, apiKey);
        JsonNode message = root.get("message");
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
            throw new AIProviderException(name + " 输出为空，可能推理内容占满 max-tokens，请调大 max-tokens 或设置 thinking: disabled");
        }
        return text;
    }
}
