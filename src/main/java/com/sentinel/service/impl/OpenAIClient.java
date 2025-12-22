package com.sentinel.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.properties.AIProperties;
import com.sentinel.service.AIClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容 API 客户端实现
 * 
 * @author Sentinel Team
 */
@Slf4j
@Component("openaiClient")
public class OpenAIClient implements AIClient {

    private final OkHttpClient httpClient;
    private final AIProperties.OpenAIConfig config;
    private final ObjectMapper objectMapper;

    public OpenAIClient(AIProperties aiProperties) {
        this.config = aiProperties.getOpenai();
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
            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", config.getModel());
            requestBody.put("messages", Collections.singletonList(message));
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 2000);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("OpenAI API 请求失败: code={}", response.code());
                    throw new RuntimeException("OpenAI API 请求失败: " + response.code());
                }

                String responseBody = response.body().string();
                JsonNode jsonNode = objectMapper.readTree(responseBody);
                return jsonNode.get("choices").get(0).get("message").get("content").asText();
            }
        } catch (Exception e) {
            log.error("OpenAI 诊断失败", e);
            throw new RuntimeException("OpenAI 诊断失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/models")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log.warn("OpenAI 服务不可用: {}", e.getMessage());
            return false;
        }
    }
}
