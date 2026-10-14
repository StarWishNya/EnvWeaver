package com.sentinel.ai;

import com.sentinel.config.properties.AIProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml 的 ai 配置能绑定到 AIProperties。
 */
@DisplayName("AI 配置绑定")
public class AIPropertiesBindingTest {

    @Test
    @DisplayName("逗号分隔的 provider-order 会去掉空白")
    public void splitsAndTrimsProviderOrder() {
        AIProperties properties = new AIProperties();
        properties.setProviderOrder(" deepseek, ollama ,,anthropic ");
        assertThat(properties.orderedProviderNames()).containsExactly("deepseek", "ollama", "anthropic");

        properties.setProviderOrder("  ");
        assertThat(properties.orderedProviderNames()).isEmpty();
    }

    @Test
    @DisplayName("主配置绑定三种协议和 DeepSeek 提供商")
    public void bindsApplicationYaml() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addFirst);
        Binder binder = new Binder(
                ConfigurationPropertySources.from(environment.getPropertySources()),
                new PropertySourcesPlaceholdersResolver(environment),
                ApplicationConversionService.getSharedInstance());

        AIProperties ai = binder.bind("ai", Bindable.of(AIProperties.class)).get();

        if (System.getenv("AI_PROVIDER_ORDER") == null && System.getenv("AI_PROVIDER") == null) {
            assertThat(ai.orderedProviderNames()).containsExactly("ollama");
        }
        assertThat(ai.getProviders()).containsKeys(
                "ollama", "openai", "anthropic", "deepseek", "deepseek-anthropic", "custom");
        assertThat(ai.getProviders().get("ollama").getProtocol()).isEqualTo(AIProtocol.OLLAMA);
        assertThat(ai.getProviders().get("openai").getProtocol()).isEqualTo(AIProtocol.OPENAI);
        assertThat(ai.getProviders().get("anthropic").getProtocol()).isEqualTo(AIProtocol.ANTHROPIC);
        assertThat(ai.getProviders().get("deepseek").getProtocol()).isEqualTo(AIProtocol.OPENAI);
        assertThat(ai.getProviders().get("deepseek-anthropic").getProtocol()).isEqualTo(AIProtocol.ANTHROPIC);
        assertThat(ai.getProviders().get("deepseek-anthropic").getBaseUrl())
                .isEqualTo("https://api.deepseek.com/anthropic");
        assertThat(ai.getProviders().get("custom").getProtocol()).isEqualTo(AIProtocol.OPENAI);
        if (System.getenv("OLLAMA_BASE_URL") == null) {
            assertThat(ai.getProviders().get("ollama").getBaseUrl()).isEqualTo("http://localhost:11434");
        }
        if (System.getenv("OLLAMA_TIMEOUT") == null) {
            assertThat(ai.getProviders().get("ollama").getTimeout()).isEqualTo(120);
        }
        if (System.getenv("DEEPSEEK_MODEL") == null) {
            assertThat(ai.getProviders().get("deepseek").getModel()).isEqualTo("deepseek-flash");
            assertThat(ai.getProviders().get("deepseek-anthropic").getModel()).isEqualTo("deepseek-flash");
        }
        if (System.getenv("DEEPSEEK_BASE_URL") == null) {
            assertThat(ai.getProviders().get("deepseek").getBaseUrl()).isEqualTo("https://api.deepseek.com");
        }
        if (System.getenv("DEEPSEEK_THINKING") == null) {
            assertThat(ai.getProviders().get("deepseek").getThinking()).isEqualTo("disabled");
            assertThat(ai.getProviders().get("deepseek-anthropic").getThinking()).isEqualTo("disabled");
        }
        assertThat(ai.getProviders().get("openai").getThinking()).isNull();
        assertThat(ai.getProviders().get("ollama").getThinking()).isNull();
        assertThat(ai.getProviders().get("anthropic").getThinking()).isNull();
        assertThat(ai.getProviders().get("openai").getMaxTokens()).isEqualTo(2000);
        assertThat(ai.getProviders().get("openai").getTemperature()).isEqualTo(0.3);
        assertThat(ai.getProviders().get("openai").getTimeout()).isEqualTo(60);
        assertThat(ai.getProviders().get("openai").getEnabled()).isTrue();
    }
}
