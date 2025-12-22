package com.sentinel.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.properties.AIProperties;
import com.sentinel.service.AIClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Ollama AI 客户端实现
 * 
 * @author Sentinel Team
 */
@Slf4j
@Component("ollamaClient")
public class OllamaAIClient implements AIClient {

    private final OkHttpClient httpClient;
    private final AIProperties.OllamaConfig config;
    private final ObjectMapper objectMapper;

    public OllamaAIClient(AIProperties aiProperties) {
        this.config = aiProperties.getOllama();
        this.objectMapper = new ObjectMapper();
        
        long timeoutSeconds = config.getTimeout().longValue();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String diagnose(String prompt) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", config.getModel());
            requestBody.put("prompt", prompt);
            requestBody.put("stream", false);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/api/generate")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Ollama API 请求失败: code={}", response.code());
                    throw new RuntimeException("Ollama API 请求失败: " + response.code());
                }

                String responseBody = response.body().string();
                JsonNode jsonNode = objectMapper.readTree(responseBody);
                return jsonNode.get("response").asText();
            }
        } catch (Exception e) {
            log.error("Ollama 诊断失败", e);
            throw new RuntimeException("Ollama 诊断失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/api/tags")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log.warn("Ollama 服务不可用: {}", e.getMessage());
            return false;
        }
    }
}
