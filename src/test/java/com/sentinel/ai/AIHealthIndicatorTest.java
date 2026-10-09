package com.sentinel.ai;

import com.sentinel.config.properties.AIProperties;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 健康检查不访问网络，未配置时也不报 DOWN。
 */
@DisplayName("AI 健康检查")
public class AIHealthIndicatorTest {

    @Test
    @DisplayName("已配置时为 UP，明细含协议、模型和 configured，且不访问网络")
    public void upWhenConfiguredWithoutNetwork() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            Map<String, AIProperties.Provider> providers = new LinkedHashMap<>();
            providers.put("ollama", AiClients.provider(AIProtocol.OLLAMA, server.url("/").toString(),
                    AiClients.API_KEY, "qwen", 5, 20, 0.1));
            providers.put("openai", AiClients.provider(AIProtocol.OPENAI, "https://example.invalid/v1",
                    "", "gpt", 5, 20, 0.1));
            AIHealthIndicator indicator = indicator("ollama,openai", providers);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) health.getDetails().get("providers");
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0))
                    .containsEntry("name", "ollama")
                    .containsEntry("protocol", "OLLAMA")
                    .containsEntry("model", "qwen")
                    .containsEntry("configured", true);
            assertThat(rows.get(1))
                    .containsEntry("name", "openai")
                    .containsEntry("protocol", "OPENAI")
                    .containsEntry("configured", false);
            assertThat(health.getDetails().toString()).doesNotContain(AiClients.API_KEY);
            assertThat(server.getRequestCount()).isZero();
        } finally {
            server.shutdown();
        }
    }

    @Test
    @DisplayName("全部未配置时为 UNKNOWN，不是 DOWN")
    public void unknownWhenNoneConfigured() {
        Map<String, AIProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("openai", AiClients.provider(AIProtocol.OPENAI, "https://example.invalid/v1",
                " ", "gpt", 5, 20, 0.1));
        providers.put("ollama", AiClients.provider(AIProtocol.OLLAMA, "", null, "qwen", 5, 20, 0.1));

        Health health = indicator("openai,ollama", providers).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
    }

    private static AIHealthIndicator indicator(String order, Map<String, AIProperties.Provider> providers) {
        AIProperties properties = new AIProperties();
        properties.setProviderOrder(order);
        properties.setProviders(providers);
        AIProviderClientFactory factory = new AIProviderClientFactory(properties);
        return new AIHealthIndicator(new AIRouter(properties, factory));
    }
}
