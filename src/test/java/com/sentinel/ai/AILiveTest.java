package com.sentinel.ai;

import com.sentinel.config.properties.AIProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeepSeek 在线协议测试。默认 surefire 排除 live 分组。
 */
@Tag("live")
@DisplayName("DeepSeek 在线协议")
public class AILiveTest {

    private static final int LIVE_MAX_TOKENS = 256;
    private static final int LIVE_TIMEOUT_SECONDS = 60;

    @Test
    @DisplayName("OpenAI 协议调用 deepseek-flash 返回非空内容")
    public void openAiProtocolReturnsContent() {
        AIProperties.Provider config = liveProvider(AIProtocol.OPENAI, "https://api.deepseek.com");
        OpenAICompatibleClient client = new OpenAICompatibleClient("deepseek", config);

        AIResponse response = client.complete(new AIRequest(
                "You only output the answer, with no explanation.",
                "Reply with exactly: pong"));

        assertThat(response.provider()).isEqualTo("deepseek");
        assertThat(response.model()).isEqualTo("deepseek-flash");
        assertThat(response.content()).isNotBlank();
    }

    @Test
    @DisplayName("Anthropic 协议调用 deepseek-flash 返回非空内容")
    public void anthropicProtocolReturnsContent() {
        AIProperties.Provider config = liveProvider(AIProtocol.ANTHROPIC, "https://api.deepseek.com/anthropic");
        AnthropicClient client = new AnthropicClient("deepseek-anthropic", config);

        AIResponse response = client.complete(new AIRequest(
                "You only output the answer, with no explanation.",
                "Reply with exactly: pong"));

        assertThat(response.provider()).isEqualTo("deepseek-anthropic");
        assertThat(response.model()).isEqualTo("deepseek-flash");
        assertThat(response.content()).isNotBlank();
    }

    private static AIProperties.Provider liveProvider(AIProtocol protocol, String baseUrl) {
        String apiKey = requireApiKey();
        AIProperties.Provider provider = new AIProperties.Provider();
        provider.setProtocol(protocol);
        provider.setBaseUrl(baseUrl);
        provider.setApiKey(apiKey);
        provider.setModel("deepseek-flash");
        provider.setTimeout(LIVE_TIMEOUT_SECONDS);
        provider.setMaxTokens(LIVE_MAX_TOKENS);
        provider.setTemperature(0.0);
        provider.setEnabled(true);
        provider.setThinking("disabled");
        return provider;
    }

    private static String requireApiKey() {
        String key = resolveApiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY 未在环境变量或仓库根 .env 中配置");
        }
        return key.trim();
    }

    private static String resolveApiKey() {
        String fromEnv = System.getenv("DEEPSEEK_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve(".env");
            if (Files.isRegularFile(candidate)) {
                String parsed = parseEnvValue(candidate);
                if (parsed != null && !parsed.isBlank()) {
                    return parsed.trim();
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static String parseEnvValue(Path file) {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("export ")) {
                    trimmed = trimmed.substring("export ".length()).trim();
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                if (!"DEEPSEEK_API_KEY".equals(key)) {
                    continue;
                }
                String value = trimmed.substring(eq + 1).trim();
                if (value.length() >= 2) {
                    char first = value.charAt(0);
                    char last = value.charAt(value.length() - 1);
                    if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                        value = value.substring(1, value.length() - 1);
                    }
                }
                return value;
            }
            return null;
        } catch (Exception ex) {
            throw new IllegalStateException("无法读取 .env");
        }
    }
}
