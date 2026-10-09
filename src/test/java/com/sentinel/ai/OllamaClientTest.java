package com.sentinel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.properties.AIProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ollama 协议客户端测试。
 */
@DisplayName("Ollama 客户端")
public class OllamaClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;

    @BeforeEach
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    @DisplayName("请求走 /api/chat，温度和 num_predict 来自配置")
    public void sendsConfiguredRequestAndParsesContent() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":{\"role\":\"assistant\",\"content\":\"pong\"}}"));

        OllamaClient client = client();
        AIResponse response = client.complete(new AIRequest("sys-prompt", "user-prompt"));

        assertThat(response.provider()).isEqualTo("ollama");
        assertThat(response.model()).isEqualTo("unit-model");
        assertThat(response.content()).isEqualTo("pong");
        assertThat(client.isConfigured()).isTrue();

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/api/chat");
        assertThat(request.getHeader("Authorization")).isNull();

        JsonNode json = objectMapper.readTree(request.getBody().readUtf8());
        assertThat(json.get("model").asText()).isEqualTo("unit-model");
        assertThat(json.get("stream").asBoolean()).isFalse();
        assertThat(json.has("max_tokens")).isFalse();
        assertThat(json.get("options").get("temperature").asDouble()).isEqualTo(0.25);
        assertThat(json.get("options").get("num_predict").asInt()).isEqualTo(321);
        assertThat(json.get("messages")).hasSize(2);
        assertThat(json.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(json.get("messages").get(0).get("content").asText()).isEqualTo("sys-prompt");
        assertThat(json.get("messages").get(1).get("role").asText()).isEqualTo("user");
        assertThat(json.get("messages").get(1).get("content").asText()).isEqualTo("user-prompt");
        assertThat(json.has("format")).isFalse();
    }

    @Test
    @DisplayName("jsonOutput 为 true 时附带 format=json，默认不附带")
    public void jsonOutputControlsFormat() throws Exception {
        server.enqueue(chat("plain"));
        server.enqueue(chat("json"));

        client().complete(new AIRequest("sys", "user"));
        client().complete(new AIRequest("sys", "user", true));

        JsonNode plain = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(plain.has("format")).isFalse();
        assertThat(plain.has("response_format")).isFalse();

        JsonNode jsonMode = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(jsonMode.get("format").asText()).isEqualTo("json");
        assertThat(jsonMode.has("response_format")).isFalse();
        assertThat(jsonMode.get("stream").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("非 2xx 带上状态码且异常不包含密钥")
    public void non2xxDoesNotLeakKey() throws Exception {
        String marker = "UNIQUE_TAIL_MARKER_PAST_LIMIT";
        String body = "ollama " + AiClients.API_KEY + " " + "C".repeat(200) + marker;
        server.enqueue(new MockResponse().setResponseCode(500).setBody(body));
        OllamaClient client = new OllamaClient("ollama",
                AiClients.provider(AIProtocol.OLLAMA, server.url("/").toString(), AiClients.API_KEY,
                        "unit-model", 5, 321, 0.25));

        assertThatThrownBy(() -> client.complete(new AIRequest("sys", "user")))
                .isInstanceOf(AIProviderException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).contains("500");
                    assertThat(ex.getMessage()).doesNotContain(marker);
                    assertThat(ex.getMessage()).doesNotContain(AiClients.API_KEY);
                    assertThat(ex.toString()).doesNotContain(AiClients.API_KEY);
                });
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(request.getHeader("x-api-key")).isNull();
    }

    @Test
    @DisplayName("thinking 为 disabled、enabled 或不设置时，请求体分别带上或省略 think")
    public void thinkingModeIsWrittenIntoRequest() throws Exception {
        server.enqueue(chat("disabled-ok"));
        server.enqueue(chat("enabled-ok"));
        server.enqueue(chat("unset-ok"));

        completeWithThinking("disabled");
        completeWithThinking("enabled");
        completeWithThinking(null);

        JsonNode disabled = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(disabled.get("think").asBoolean()).isFalse();
        assertThat(disabled.has("thinking")).isFalse();

        JsonNode enabled = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(enabled.get("think").asBoolean()).isTrue();

        JsonNode unset = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(unset.has("think")).isFalse();
        assertThat(unset.has("thinking")).isFalse();
    }

    @Test
    @DisplayName("空白 content 抛出 AIProviderException")
    public void blankContentIsFailure() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"message\":{\"role\":\"assistant\",\"content\":\"  \"}}"));

        assertThatThrownBy(() -> client().complete(new AIRequest("sys", "user")))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("输出为空");
    }

    @Test
    @DisplayName("缺少 message.content 时抛出 AIProviderException")
    public void rejectsMissingContent() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"message\":{}}"));

        assertThatThrownBy(() -> client().complete(new AIRequest("sys", "user")))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("content");
    }

    @Test
    @DisplayName("只需要 base-url，空地址视为未配置")
    public void configuredRequiresBaseUrlOnly() {
        assertThat(client().isConfigured()).isTrue();
        AIProperties.Provider blank = AiClients.provider(AIProtocol.OLLAMA, "  ", null, "unit-model", 5, 321, 0.25);
        assertThat(new OllamaClient("ollama", blank).isConfigured()).isFalse();
    }

    private OllamaClient client() {
        return new OllamaClient("ollama",
                AiClients.provider(AIProtocol.OLLAMA, server.url("/").toString(), null,
                        "unit-model", 5, 321, 0.25));
    }

    private void completeWithThinking(String thinking) {
        AIProperties.Provider config = AiClients.provider(AIProtocol.OLLAMA, server.url("/").toString(),
                null, "unit-model", 5, 321, 0.25);
        config.setThinking(thinking);
        new OllamaClient("ollama", config).complete(new AIRequest("sys", "user"));
    }

    private JsonNode body(RecordedRequest request) throws Exception {
        assertThat(request).isNotNull();
        return objectMapper.readTree(request.getBody().readUtf8());
    }

    private static MockResponse chat(String content) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}");
    }
}
