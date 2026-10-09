package com.sentinel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 协议客户端共用的 HTTP 与 JSON 工具。异常文本会先抹掉密钥再截断。
 */
final class AIHttp {

    static final int DEFAULT_TIMEOUT_SECONDS = 60;
    static final int DEFAULT_MAX_TOKENS = 2000;
    static final double DEFAULT_TEMPERATURE = 0.3;
    static final int ERROR_SNIPPET_LIMIT = 200;

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AIHttp() {
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }

    static OkHttpClient newClient(Integer timeoutSeconds) {
        int timeout = timeoutSeconds == null || timeoutSeconds <= 0
                ? DEFAULT_TIMEOUT_SECONDS
                : timeoutSeconds;
        return new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    static int maxTokens(Integer value) {
        return value == null ? DEFAULT_MAX_TOKENS : value;
    }

    static double temperature(Double value) {
        return value == null ? DEFAULT_TEMPERATURE : value;
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 按配置写入推理开关。未设置时不写字段，避免官方接口拒绝未知参数。
     * Anthropic 的 enabled 还要 budget_tokens，且必须小于 max_tokens；
     * 放不下（max_tokens 不大于 1024）时整段 thinking 都不发送。
     */
    static void applyThinking(ObjectNode root, AIProtocol protocol, String thinking, int maxTokens) {
        String mode = normalizeThinking(thinking);
        if (mode == null || root == null || protocol == null) {
            return;
        }
        if (protocol == AIProtocol.OLLAMA) {
            root.put("think", "enabled".equals(mode));
            return;
        }
        if ("disabled".equals(mode)) {
            root.putObject("thinking").put("type", "disabled");
            return;
        }
        if (protocol == AIProtocol.ANTHROPIC && maxTokens <= 1024) {
            return;
        }
        ObjectNode node = root.putObject("thinking");
        node.put("type", "enabled");
        if (protocol == AIProtocol.ANTHROPIC) {
            node.put("budget_tokens", Math.max(1024, maxTokens / 2));
        }
    }

    static String blankOutputMessage(String provider, String reasonField, String reason) {
        String shown = hasText(reason) ? reason.trim() : "unknown";
        return provider + " 输出为空(" + reasonField + "=" + shown
                + ")，可能推理内容占满 max-tokens，请调大 max-tokens 或设置 thinking: disabled";
    }

    static String fieldText(JsonNode node, String field) {
        if (node == null || field == null) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "";
        }
        return value.asText("");
    }

    private static String normalizeThinking(String thinking) {
        if (thinking == null || thinking.isBlank()) {
            return null;
        }
        String mode = thinking.trim().toLowerCase();
        if ("unset".equals(mode)) {
            return null;
        }
        if ("disabled".equals(mode) || "enabled".equals(mode)) {
            return mode;
        }
        return null;
    }

    static String endpoint(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (path == null || path.isEmpty()) {
            return base;
        }
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    static String post(OkHttpClient httpClient, String provider, String secret, String url,
                       Map<String, String> headers, String json) {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (name != null && value != null) {
                    builder.header(name, value);
                }
            });
        }
        builder.post(RequestBody.create(json, JSON_MEDIA));
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new AIProviderException(provider + " HTTP " + response.code() + ": " + snippet(body, secret));
            }
            return body;
        } catch (AIProviderException ex) {
            throw ex;
        } catch (IOException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            throw new AIProviderException(provider + " 请求失败: " + snippet(reason, secret), ex);
        }
    }

    static JsonNode readTree(String body, String provider, String secret) {
        if (!hasText(body)) {
            throw new AIProviderException(provider + " 响应为空");
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node == null || node.isNull() || node.isMissingNode()) {
                throw new AIProviderException(provider + " 响应为空");
            }
            return node;
        } catch (AIProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            throw new AIProviderException(provider + " 响应解析失败: " + snippet(reason, secret), ex);
        }
    }

    static String snippet(String text, String secret) {
        String value = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim();
        value = redact(value, secret);
        if (value.length() > ERROR_SNIPPET_LIMIT) {
            return value.substring(0, ERROR_SNIPPET_LIMIT);
        }
        return value;
    }

    static String redact(String text, String secret) {
        if (text == null || text.isEmpty() || secret == null || secret.isBlank()) {
            return text == null ? "" : text;
        }
        return text.replace(secret, "***");
    }
}
