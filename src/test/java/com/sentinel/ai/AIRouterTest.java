package com.sentinel.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.AIProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AIRouter 回退顺序测试。
 */
@DisplayName("AI 路由")
public class AIRouterTest {

    @Test
    @DisplayName("第一个提供商失败时使用第二个")
    public void fallsBackWhenFirstProviderFails() throws Exception {
        MockWebServer first = new MockWebServer();
        MockWebServer second = new MockWebServer();
        first.start();
        second.start();
        try {
            first.enqueue(new MockResponse().setResponseCode(500)
                    .setBody("alpha-down " + AiClients.API_KEY));
            second.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"from-second\"}}]}"));

            Map<String, AIProperties.Provider> providers = new LinkedHashMap<>();
            providers.put("alpha", openai(first, "model-a"));
            providers.put("beta", openai(second, "model-b"));
            AIRouter router = AiClients.router("alpha,beta", providers);

            AIResponse response = router.complete(new AIRequest("sys", "user"));

            assertThat(response.content()).isEqualTo("from-second");
            assertThat(response.provider()).isEqualTo("beta");
            assertThat(response.model()).isEqualTo("model-b");
            assertThat(first.getRequestCount()).isEqualTo(1);
            assertThat(second.getRequestCount()).isEqualTo(1);
            assertThat(first.takeRequest().getBody().readUtf8()).contains("model-a");
            assertThat(second.takeRequest().getBody().readUtf8()).contains("model-b");
        } finally {
            first.shutdown();
            second.shutdown();
        }
    }

    @Test
    @DisplayName("第一个提供商只返回空白正文时改用第二个")
    public void fallsBackWhenFirstProviderReturnsBlankContent() throws Exception {
        MockWebServer first = new MockWebServer();
        MockWebServer second = new MockWebServer();
        first.start();
        second.start();
        try {
            first.enqueue(json("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\"}}]}"));
            second.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"from-second\"}}]}"));

            Map<String, AIProperties.Provider> providers = new LinkedHashMap<>();
            providers.put("alpha", openai(first, "model-a"));
            providers.put("beta", openai(second, "model-b"));
            AIRouter router = AiClients.router("alpha,beta", providers);

            AIResponse response = router.complete(new AIRequest("sys", "user"));

            assertThat(response.provider()).isEqualTo("beta");
            assertThat(response.content()).isEqualTo("from-second");
            assertThat(first.getRequestCount()).isEqualTo(1);
            assertThat(second.getRequestCount()).isEqualTo(1);
        } finally {
            first.shutdown();
            second.shutdown();
        }
    }

    @Test
    @DisplayName("调用顺序以 provider-order 为准，而不是配置声明顺序")
    public void respectsProviderOrder() throws Exception {
        MockWebServer alpha = new MockWebServer();
        MockWebServer beta = new MockWebServer();
        alpha.start();
        beta.start();
        try {
            beta.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"beta-first\"}}]}"));
            Map<String, AIProperties.Provider> providers = new LinkedHashMap<>();
            providers.put("alpha", openai(alpha, "model-a"));
            providers.put("beta", openai(beta, "model-b"));
            AIRouter router = AiClients.router("beta,alpha", providers);

            AIResponse response = router.complete(new AIRequest("sys", "user"));

            assertThat(response.provider()).isEqualTo("beta");
            assertThat(response.content()).isEqualTo("beta-first");
            assertThat(alpha.getRequestCount()).isZero();
            assertThat(beta.getRequestCount()).isEqualTo(1);
        } finally {
            alpha.shutdown();
            beta.shutdown();
        }
    }

    @Test
    @DisplayName("未配置的提供商被跳过")
    public void skipsUnconfiguredProvider() throws Exception {
        MockWebServer idle = new MockWebServer();
        MockWebServer live = new MockWebServer();
        idle.start();
        live.start();
        try {
            live.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
            Map<String, AIProperties.Provider> providers = new LinkedHashMap<>();
            providers.put("bare", AiClients.provider(AIProtocol.OPENAI, idle.url("/").toString(), "",
                    "model-x", 5, 20, 0.1));
            providers.put("good", openai(live, "model-b"));
            AIRouter router = AiClients.router("bare,good", providers);

            AIResponse response = router.complete(new AIRequest("sys", "user"));

            assertThat(response.provider()).isEqualTo("good");
            assertThat(idle.getRequestCount()).isZero();
            assertThat(live.getRequestCount()).isEqualTo(1);
        } finally {
            idle.shutdown();
            live.shutdown();
        }
    }

    @Test
    @DisplayName("已禁用的提供商被跳过")
    public void skipsDisabledProvider() throws Exception {
        MockWebServer idle = new MockWebServer();
        MockWebServer live = new MockWebServer();
        idle.start();
        live.start();
        try {
            live.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
            AIProperties.Provider disabled = openai(idle, "model-a");
            disabled.setEnabled(false);
            Map<String, AIProperties.Provider> providers = new LinkedHashMap<>();
            providers.put("off", disabled);
            providers.put("good", openai(live, "model-b"));
            AIRouter router = AiClients.router("off,good", providers);

            assertThat(router.complete(new AIRequest("sys", "user")).provider()).isEqualTo("good");
            assertThat(idle.getRequestCount()).isZero();
        } finally {
            idle.shutdown();
            live.shutdown();
        }
    }

    @Test
    @DisplayName("全部失败时抛出 4003，并列出尝试过的提供商")
    public void allFailThrows4003() throws Exception {
        MockWebServer first = new MockWebServer();
        MockWebServer second = new MockWebServer();
        first.start();
        second.start();
        try {
            first.enqueue(new MockResponse().setResponseCode(502).setBody("bad-" + AiClients.API_KEY));
            second.enqueue(new MockResponse().setResponseCode(503).setBody("also-bad"));
            Map<String, AIProperties.Provider> providers = new LinkedHashMap<>();
            providers.put("alpha", openai(first, "model-a"));
            providers.put("beta", openai(second, "model-b"));
            AIRouter router = AiClients.router("alpha,beta", providers);

            assertThatThrownBy(() -> router.complete(new AIRequest("sys", "user")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException business = (BusinessException) ex;
                        assertThat(business.getCode()).isEqualTo(4003);
                        assertThat(business.getMessage()).contains("alpha", "beta");
                        assertThat(business.getMessage()).doesNotContain(AiClients.API_KEY);
                        assertThat(business.toString()).doesNotContain(AiClients.API_KEY);
                    });
        } finally {
            first.shutdown();
            second.shutdown();
        }
    }

    @Test
    @DisplayName("没有已配置提供商时抛出 4003")
    public void emptyEffectiveListThrows4003() throws Exception {
        MockWebServer idle = new MockWebServer();
        idle.start();
        try {
            Map<String, AIProperties.Provider> providers = new LinkedHashMap<>();
            providers.put("bare", AiClients.provider(AIProtocol.OPENAI, idle.url("/").toString(), null,
                    "model-x", 5, 20, 0.1));
            AIRouter router = AiClients.router("bare", providers);

            assertThatThrownBy(() -> router.complete(new AIRequest("sys", "user")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(4003));
            assertThat(idle.getRequestCount()).isZero();
        } finally {
            idle.shutdown();
        }
    }

    @Test
    @DisplayName("未知提供商只警告一次并跳过")
    public void unknownProviderWarnedOnceAndSkipped() throws Exception {
        MockWebServer live = new MockWebServer();
        live.start();
        Logger logger = (Logger) LoggerFactory.getLogger(AIRouter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            live.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
            Map<String, AIProperties.Provider> providers = new LinkedHashMap<>();
            providers.put("good", openai(live, "model-b"));
            AIRouter router = AiClients.router("ghost,ghost,good", providers);

            assertThat(router.complete(new AIRequest("sys", "user")).provider()).isEqualTo("good");
            long warnings = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .filter(event -> event.getFormattedMessage().contains("ghost"))
                    .count();
            assertThat(warnings).isEqualTo(1);
            assertThat(live.getRequestCount()).isEqualTo(1);
        } finally {
            logger.detachAppender(appender);
            live.shutdown();
        }
    }

    @Test
    @DisplayName("状态查询不发起网络请求")
    public void statusDoesNotCallNetwork() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            Map<String, AIProperties.Provider> providers = new LinkedHashMap<>();
            providers.put("good", openai(server, "model-b"));
            AIRouter router = AiClients.router("good", providers);

            assertThat(router.status()).singleElement().satisfies(status -> {
                assertThat(status.name()).isEqualTo("good");
                assertThat(status.protocol()).isEqualTo(AIProtocol.OPENAI);
                assertThat(status.model()).isEqualTo("model-b");
                assertThat(status.configured()).isTrue();
                assertThat(status.available()).isTrue();
            });
            assertThat(server.getRequestCount()).isZero();
        } finally {
            server.shutdown();
        }
    }

    private static AIProperties.Provider openai(MockWebServer server, String model) {
        return AiClients.provider(AIProtocol.OPENAI, server.url("/").toString(), AiClients.API_KEY,
                model, 5, 20, 0.1);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body);
    }
}
