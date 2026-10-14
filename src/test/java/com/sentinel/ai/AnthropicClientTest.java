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
 * Anthropic 兼容协议客户端测试。
 */
@DisplayName("Anthropic 兼容客户端")
public class AnthropicClientTest {

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
    @DisplayName("system 在顶层，用户消息在 messages，并拼接 type=text 的内容")
    public void sendsConfiguredRequestAndParsesContent() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"content\":["
                        + "{\"type\":\"thinking\",\"thinking\":\"hidden\"},"
                        + "{\"type\":\"text\",\"text\":\"hello \"},"
                        + "{\"type\":\"text\",\"text\":\"world\"}"
                        + "]}"));

        AnthropicClient client = client();
        AIResponse response = client.complete(new AIRequest("sys-prompt", "user-prompt"));

        assertThat(response.provider()).isEqualTo("deepseek-anthropic");
        assertThat(response.model()).isEqualTo("unit-model");
        assertThat(response.content()).isEqualTo("hello world");

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/v1/messages");
        assertThat(request.getRequestUrl().toString()).doesNotContain(AiClients.API_KEY);
        assertThat(request.getHeader("x-api-key")).isEqualTo(AiClients.API_KEY);
        assertThat(request.getHeader("anthropic-version")).isEqualTo(AnthropicClient.ANTHROPIC_VERSION);
        assertThat(request.getHeader("Authorization")).isNull();

        JsonNode json = objectMapper.readTree(request.getBody().readUtf8());
        assertThat(json.get("model").asText()).isEqualTo("unit-model");
        assertThat(json.get("temperature").asDouble()).isEqualTo(0.25);
        assertThat(json.get("max_tokens").asInt()).isEqualTo(321);
        assertThat(json.get("system").asText()).isEqualTo("sys-prompt");
        assertThat(json.get("messages")).hasSize(1);
        assertThat(json.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(json.get("messages").get(0).get("content").asText()).isEqualTo("user-prompt");
        assertThat(json.has("response_format")).isFalse();
        assertThat(json.has("format")).isFalse();
    }

    @Test
    @DisplayName("Anthropic 没有 JSON 模式，jsonOutput 为 true 时也不附加字段")
    public void jsonOutputAddsNothing() throws Exception {
        server.enqueue(message("plain"));
        server.enqueue(message("json"));

        client().complete(new AIRequest("sys", "user", false));
        client().complete(new AIRequest("sys", "user", true));

        JsonNode plain = body(server.takeRequest(1, TimeUnit.SECONDS));
        JsonNode jsonMode = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(plain.has("response_format")).isFalse();
        assertThat(plain.has("format")).isFalse();
        assertThat(jsonMode.has("response_format")).isFalse();
        assertThat(jsonMode.has("format")).isFalse();
        assertThat(jsonMode).isEqualTo(plain);
    }

    @Test
    @DisplayName("非 2xx 异常信息截断且不包含密钥")
    public void non2xxDoesNotLeakKey() {
        String marker = "UNIQUE_TAIL_MARKER_PAST_LIMIT";
        String body = "denied " + AiClients.API_KEY + " " + "B".repeat(200) + marker;
        server.enqueue(new MockResponse().setResponseCode(403).setBody(body));

        assertThatThrownBy(() -> client().complete(new AIRequest("sys", "user")))
                .isInstanceOf(AIProviderException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).contains("403");
                    assertThat(ex.getMessage()).doesNotContain(marker);
                    assertThat(ex.getMessage()).doesNotContain(AiClients.API_KEY);
                    assertThat(ex.toString()).doesNotContain(AiClients.API_KEY);
                });
    }

    @Test
    @DisplayName("thinking 为 disabled、enabled 或不设置时，请求体分别带上或省略该字段")
    public void thinkingModeIsWrittenIntoRequest() throws Exception {
        server.enqueue(message("disabled-ok"));
        server.enqueue(message("enabled-ok"));
        server.enqueue(message("unset-ok"));
        server.enqueue(message("small-ok"));

        completeWithThinking("disabled", 2048);
        completeWithThinking("enabled", 2048);
        completeWithThinking(null, 2048);
        completeWithThinking("enabled", 256);

        JsonNode disabled = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(disabled.get("thinking").get("type").asText()).isEqualTo("disabled");
        assertThat(disabled.get("thinking").has("budget_tokens")).isFalse();

        JsonNode enabled = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(enabled.get("thinking").get("type").asText()).isEqualTo("enabled");
        assertThat(enabled.get("thinking").get("budget_tokens").asInt()).isEqualTo(1024);
        assertThat(enabled.get("max_tokens").asInt()).isEqualTo(2048);

        JsonNode unset = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(unset.has("thinking")).isFalse();

        JsonNode tooSmall = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(tooSmall.has("thinking")).isFalse();
        assertThat(tooSmall.get("max_tokens").asInt()).isEqualTo(256);
    }

    @Test
    @DisplayName("只有 thinking 块且 stop_reason=max_tokens 时失败，消息包含 max_tokens")
    public void thinkingOnlyContentIsFailure() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"stop_reason\":\"max_tokens\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"hidden\"}]}"));

        assertThatThrownBy(() -> client().complete(new AIRequest("sys", "user")))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("max_tokens")
                .hasMessageContaining("text");
    }

    @Test
    @DisplayName("没有 text 块时抛出 AIProviderException")
    public void rejectsMissingText() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"x\"}]}"));

        assertThatThrownBy(() -> client().complete(new AIRequest("sys", "user")))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("text");
    }

    @Test
    @DisplayName("无密钥视为未配置")
    public void configuredRequiresBaseUrlAndKey() {
        assertThat(client().isConfigured()).isTrue();
        AIProperties.Provider missing = AiClients.provider(AIProtocol.ANTHROPIC, server.url("/").toString(),
                "", "unit-model", 5, 321, 0.25);
        assertThat(new AnthropicClient("deepseek-anthropic", missing).isConfigured()).isFalse();
    }

    private AnthropicClient client() {
        return new AnthropicClient("deepseek-anthropic",
                AiClients.provider(AIProtocol.ANTHROPIC, server.url("/").toString(), AiClients.API_KEY,
                        "unit-model", 5, 321, 0.25));
    }

    private void completeWithThinking(String thinking, int maxTokens) {
        AIProperties.Provider config = AiClients.provider(AIProtocol.ANTHROPIC, server.url("/").toString(),
                AiClients.API_KEY, "unit-model", 5, maxTokens, 0.25);
        config.setThinking(thinking);
        new AnthropicClient("deepseek-anthropic", config).complete(new AIRequest("sys", "user"));
    }

    private JsonNode body(RecordedRequest request) throws Exception {
        assertThat(request).isNotNull();
        return objectMapper.readTree(request.getBody().readUtf8());
    }

    private static MockResponse message(String text) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}");
    }
}
