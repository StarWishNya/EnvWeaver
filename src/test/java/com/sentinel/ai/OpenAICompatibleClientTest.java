package com.sentinel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.properties.AIProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI 兼容协议客户端测试。
 */
@DisplayName("OpenAI 兼容客户端")
public class OpenAICompatibleClientTest {

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
    @DisplayName("请求路径、鉴权头、模型与采样参数来自配置，并解析 content")
    public void sendsConfiguredRequestAndParsesContent() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}"));

        OpenAICompatibleClient client = client();
        AIResponse response = client.complete(new AIRequest("sys-prompt", "user-prompt"));

        assertThat(response.provider()).isEqualTo("deepseek");
        assertThat(response.model()).isEqualTo("unit-model");
        assertThat(response.content()).isEqualTo("hello");

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/chat/completions");
        assertThat(request.getRequestUrl().toString()).doesNotContain(AiClients.API_KEY);
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + AiClients.API_KEY);

        JsonNode json = objectMapper.readTree(request.getBody().readUtf8());
        assertThat(json.get("model").asText()).isEqualTo("unit-model");
        assertThat(json.get("temperature").asDouble()).isEqualTo(0.25);
        assertThat(json.get("max_tokens").asInt()).isEqualTo(321);
        assertThat(json.get("messages")).hasSize(2);
        assertThat(json.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(json.get("messages").get(0).get("content").asText()).isEqualTo("sys-prompt");
        assertThat(json.get("messages").get(1).get("role").asText()).isEqualTo("user");
        assertThat(json.get("messages").get(1).get("content").asText()).isEqualTo("user-prompt");
        assertThat(json.has("response_format")).isFalse();
    }

    @Test
    @DisplayName("jsonOutput 为 true 时附带 response_format=json_object，默认不附带")
    public void jsonOutputControlsResponseFormat() throws Exception {
        server.enqueue(chat("plain"));
        server.enqueue(chat("json"));

        client().complete(new AIRequest("sys", "user"));
        client().complete(new AIRequest("sys", "user", true));

        JsonNode plain = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(plain.has("response_format")).isFalse();
        assertThat(plain.has("format")).isFalse();

        JsonNode jsonMode = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(jsonMode.get("response_format").get("type").asText()).isEqualTo("json_object");
        assertThat(jsonMode.has("format")).isFalse();
        assertThat(jsonMode.get("model").asText()).isEqualTo("unit-model");
    }

    @Test
    @DisplayName("非 2xx 带上状态码和截断响应体，且异常不包含密钥")
    public void non2xxDoesNotLeakKey() {
        String marker = "UNIQUE_TAIL_MARKER_PAST_LIMIT";
        String body = "error key=" + AiClients.API_KEY + " " + "A".repeat(200) + marker;
        server.enqueue(new MockResponse().setResponseCode(401).setBody(body));

        assertThatThrownBy(() -> client().complete(new AIRequest("sys", "user")))
                .isInstanceOf(AIProviderException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).contains("401");
                    assertThat(ex.getMessage()).doesNotContain(marker);
                    assertThat(ex.getMessage()).doesNotContain(AiClients.API_KEY);
                    assertThat(ex.toString()).doesNotContain(AiClients.API_KEY);
                    assertThat(ex.getMessage().length()).isLessThan(300);
                });
    }

    @Test
    @DisplayName("缺少 content 或非法 JSON 时抛出 AIProviderException")
    public void rejectsIncompleteOrMalformedJson() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"choices\":null}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("not-json"));

        OpenAICompatibleClient client = client();
        assertThatThrownBy(() -> client.complete(new AIRequest("sys", "user")))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("choices");
        assertThatThrownBy(() -> client.complete(new AIRequest("sys", "user")))
                .isInstanceOf(AIProviderException.class);
    }

    @Test
    @DisplayName("读超时包装为 AIProviderException 且不含密钥")
    public void ioTimeoutThrowsProviderException() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        OpenAICompatibleClient timeoutClient = new OpenAICompatibleClient("deepseek",
                AiClients.provider(AIProtocol.OPENAI, server.url("/").toString(), AiClients.API_KEY,
                        "unit-model", 1, 321, 0.25));

        assertThatThrownBy(() -> timeoutClient.complete(new AIRequest("sys", "user")))
                .isInstanceOf(AIProviderException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).doesNotContain(AiClients.API_KEY);
                    assertThat(ex.toString()).doesNotContain(AiClients.API_KEY);
                });
    }

    @Test
    @DisplayName("thinking 为 disabled、enabled 或不设置时，请求体分别带上或省略该字段")
    public void thinkingModeIsWrittenIntoRequest() throws Exception {
        server.enqueue(chat("disabled-ok"));
        server.enqueue(chat("enabled-ok"));
        server.enqueue(chat("unset-ok"));

        completeWithThinking("disabled");
        completeWithThinking("enabled");
        completeWithThinking(null);

        JsonNode disabled = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(disabled.get("thinking").get("type").asText()).isEqualTo("disabled");
        assertThat(disabled.get("thinking").has("budget_tokens")).isFalse();

        JsonNode enabled = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(enabled.get("thinking").get("type").asText()).isEqualTo("enabled");
        assertThat(enabled.get("thinking").has("budget_tokens")).isFalse();

        JsonNode unset = body(server.takeRequest(1, TimeUnit.SECONDS));
        assertThat(unset.has("thinking")).isFalse();
    }

    @Test
    @DisplayName("空白 content 且 finish_reason=length 时失败，消息包含 length")
    public void blankContentIsFailure() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\"}}]}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"  \\n\"}}]}"));

        OpenAICompatibleClient client = client();
        assertThatThrownBy(() -> client.complete(new AIRequest("sys", "user")))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("length")
                .hasMessageContaining("thinking: disabled");
        assertThatThrownBy(() -> client.complete(new AIRequest("sys", "user")))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("length");
    }

    @Test
    @DisplayName("无密钥视为未配置，有密钥且有地址视为已配置")
    public void configuredRequiresBaseUrlAndKey() {
        assertThat(client().isConfigured()).isTrue();
        AIProperties.Provider missingKey = AiClients.provider(AIProtocol.OPENAI, server.url("/").toString(),
                "  ", "unit-model", 5, 321, 0.25);
        assertThat(new OpenAICompatibleClient("deepseek", missingKey).isConfigured()).isFalse();
    }

    private OpenAICompatibleClient client() {
        return new OpenAICompatibleClient("deepseek",
                AiClients.provider(AIProtocol.OPENAI, server.url("/").toString(), AiClients.API_KEY,
                        "unit-model", 5, 321, 0.25));
    }

    private void completeWithThinking(String thinking) {
        AIProperties.Provider config = AiClients.provider(AIProtocol.OPENAI, server.url("/").toString(),
                AiClients.API_KEY, "unit-model", 5, 321, 0.25);
        config.setThinking(thinking);
        new OpenAICompatibleClient("deepseek", config).complete(new AIRequest("sys", "user"));
    }

    private JsonNode body(RecordedRequest request) throws Exception {
        assertThat(request).isNotNull();
        return objectMapper.readTree(request.getBody().readUtf8());
    }

    private static MockResponse chat(String content) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}");
    }
}
