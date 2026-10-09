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
 * Anthropic Messages 兼容客户端。
 * POST {base-url}/v1/messages，请求头 x-api-key 与 anthropic-version。
 */
public final class AnthropicClient implements AIProviderClient {

    static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String name;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final int maxTokens;
    private final double temperature;
    private final String thinking;
    private final OkHttpClient httpClient;

    public AnthropicClient(String name, AIProperties.Provider config) {
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
        return AIProtocol.ANTHROPIC;
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
            String url = AIHttp.endpoint(baseUrl, "/v1/messages");
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
            headers.put("x-api-key", apiKey);
        }
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        return headers;
    }

    private String requestJson(AIRequest request) {
        // Anthropic Messages 没有 JSON mode。jsonOutput 为 true 时也不附加 response_format 或 format。
        ObjectNode root = AIHttp.mapper().createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("temperature", temperature);
        if (request != null && request.systemPrompt() != null) {
            root.put("system", request.systemPrompt());
        }
        ArrayNode messages = root.putArray("messages");
        String userPrompt = request == null || request.userPrompt() == null ? "" : request.userPrompt();
        messages.addObject().put("role", "user").put("content", userPrompt);
        AIHttp.applyThinking(root, AIProtocol.ANTHROPIC, thinking, maxTokens);
        try {
            return AIHttp.mapper().writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new AIProviderException(name + " 请求序列化失败", ex);
        }
    }

    private String parseContent(String body) {
        JsonNode root = AIHttp.readTree(body, name, apiKey);
        JsonNode content = root.get("content");
        if (content == null || content.isNull() || !content.isArray()) {
            throw new AIProviderException(name + " 响应缺少 content");
        }
        StringBuilder text = new StringBuilder();
        boolean found = false;
        for (JsonNode part : content) {
            if (part == null || !part.isObject()) {
                continue;
            }
            JsonNode type = part.get("type");
            if (type == null || type.isNull() || !"text".equals(type.asText())) {
                continue;
            }
            JsonNode partText = part.get("text");
            if (partText == null || partText.isNull()) {
                continue;
            }
            text.append(partText.asText(""));
            found = true;
        }
        String answer = text.toString();
        if (!found || answer.isBlank()) {
            String stopReason = AIHttp.fieldText(root, "stop_reason");
            String shown = AIHttp.hasText(stopReason) ? stopReason.trim() : "unknown";
            String detail = found
                    ? AIHttp.blankOutputMessage(name, "stop_reason", shown)
                    : name + " 响应缺少 text(stop_reason=" + shown
                    + ")，可能推理内容占满 max-tokens，请调大 max-tokens 或设置 thinking: disabled";
            throw new AIProviderException(detail);
        }
        return answer;
    }
}
