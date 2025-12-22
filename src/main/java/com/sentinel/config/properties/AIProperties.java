package com.sentinel.config.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI 服务配置属性
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai")
public class AIProperties {

    /**
     * AI 提供商类型（ollama/openai/custom）
     */
    @NotBlank(message = "AI 提供商类型不能为空")
    private String provider = "ollama";

    /**
     * Ollama 配置
     */
    @NotNull(message = "Ollama 配置不能为空")
    private OllamaConfig ollama = new OllamaConfig();

    /**
     * OpenAI 配置
     */
    @NotNull(message = "OpenAI 配置不能为空")
    private OpenAIConfig openai = new OpenAIConfig();

    /**
     * 自定义 AI 配置
     */
    @NotNull(message = "自定义 AI 配置不能为空")
    private CustomConfig custom = new CustomConfig();

    /**
     * Ollama 配置类
     */
    @Data
    public static class OllamaConfig {
        /**
         * Ollama 服务地址
         */
        @NotBlank(message = "Ollama 服务地址不能为空")
        private String baseUrl = "http://localhost:11434";

        /**
         * 使用的模型名称
         */
        @NotBlank(message = "Ollama 模型名称不能为空")
        private String model = "qwen2.5:7b";

        /**
         * 请求超时时间（秒）
         */
        @Min(value = 10, message = "超时时间不能少于10秒")
        private Integer timeout = 120;

        /**
         * 是否使用流式响应
         */
        private Boolean stream = false;

        /**
         * 温度参数（0.0-1.0）
         */
        private Double temperature = 0.7;
    }

    /**
     * OpenAI 配置类
     */
    @Data
    public static class OpenAIConfig {
        /**
         * OpenAI API 地址
         */
        @NotBlank(message = "OpenAI API 地址不能为空")
        private String baseUrl = "https://api.openai.com/v1";

        /**
         * API 密钥
         */
        private String apiKey;

        /**
         * 使用的模型名称
         */
        @NotBlank(message = "OpenAI 模型名称不能为空")
        private String model = "gpt-3.5-turbo";

        /**
         * 请求超时时间（秒）
         */
        @Min(value = 10, message = "超时时间不能少于10秒")
        private Integer timeout = 60;

        /**
         * 最大 token 数
         */
        @Min(value = 100, message = "最大 token 数不能少于100")
        private Integer maxTokens = 2000;

        /**
         * 温度参数（0.0-1.0）
         */
        private Double temperature = 0.7;
    }

    /**
     * 自定义 AI 配置类（OpenAI 兼容 API）
     */
    @Data
    public static class CustomConfig {
        /**
         * 自定义 AI API 地址
         */
        private String baseUrl;

        /**
         * API 密钥
         */
        private String apiKey;

        /**
         * 使用的模型名称
         */
        private String model;

        /**
         * 请求超时时间（秒）
         */
        @Min(value = 10, message = "超时时间不能少于10秒")
        private Integer timeout = 60;

        /**
         * 最大 token 数
         */
        @Min(value = 100, message = "最大 token 数不能少于100")
        private Integer maxTokens = 2000;

        /**
         * 温度参数（0.0-1.0）
         */
        private Double temperature = 0.7;
    }
}
