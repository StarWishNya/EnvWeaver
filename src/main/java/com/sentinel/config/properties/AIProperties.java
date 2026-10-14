package com.sentinel.config.properties;

import com.sentinel.ai.AIProtocol;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 服务配置。
 * provider-order 为逗号分隔的提供商名称，第一项优先。
 * 每个 providers 条目可选 timeout（秒，默认 60）、max-tokens（默认 2000）、
 * temperature（默认 0.3）、enabled（默认 true）、thinking（不设置 / disabled / enabled）。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai")
public class AIProperties {

    /**
     * 逗号分隔的调用顺序。未配置时默认 ollama。
     */
    private String providerOrder = "ollama";

    /**
     * 已命名的提供商。键是提供商名称，例如 deepseek。
     */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    /**
     * 按配置顺序返回去掉空白后的提供商名称。
     */
    public List<String> orderedProviderNames() {
        if (providerOrder == null || providerOrder.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String part : providerOrder.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /**
     * 单个提供商配置。
     */
    @Data
    public static class Provider {

        /**
         * 协议：openai / anthropic / ollama。
         */
        private AIProtocol protocol;

        /**
         * 服务根地址，末尾斜杠会被忽略。
         */
        private String baseUrl;

        /**
         * API 密钥。Ollama 不使用。不进入 toString，避免日志带出密钥。
         */
        @ToString.Exclude
        private String apiKey;

        /**
         * 模型名称。
         */
        private String model;

        /**
         * 超时时间（秒），默认 60。
         */
        private Integer timeout = 60;

        /**
         * 最大生成 token 数，默认 2000。Ollama 映射为 num_predict。
         */
        private Integer maxTokens = 2000;

        /**
         * 温度，默认 0.3。
         */
        private Double temperature = 0.3;

        /**
         * 是否参与调用，默认 true。
         */
        private Boolean enabled = true;

        /**
         * 推理开关：不设置（null、空白或 unset）时不发送该字段；
         * disabled 关闭思考；enabled 打开思考。
         */
        private String thinking;
    }
}
