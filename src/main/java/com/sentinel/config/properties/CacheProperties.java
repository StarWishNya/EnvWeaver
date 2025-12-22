package com.sentinel.config.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 缓存配置属性
 */
@Data
@Validated
@ConfigurationProperties(prefix = "cache")
public class CacheProperties {

    /**
     * AI 诊断缓存 TTL（秒）
     * 默认：24小时
     */
    @Min(value = 60, message = "诊断缓存 TTL 不能少于60秒")
    private Long diagnosisTtl = 86400L;

    /**
     * 系统指标缓存 TTL（秒）
     * 默认：5分钟
     */
    @Min(value = 10, message = "指标缓存 TTL 不能少于10秒")
    private Long metricsTtl = 300L;

    /**
     * Token 黑名单缓存 TTL（秒）
     * 默认：24小时
     */
    @Min(value = 60, message = "Token 黑名单 TTL 不能少于60秒")
    private Long tokenBlacklistTtl = 86400L;

    /**
     * 缓存键前缀
     */
    @NotBlank(message = "缓存键前缀不能为空")
    private String keyPrefix = "sentinel:";

    /**
     * 获取诊断缓存键
     */
    public String getDiagnosisKey(String logHash) {
        return keyPrefix + "diagnosis:" + logHash;
    }

    /**
     * 获取指标缓存键
     */
    public String getMetricsKey(String metricName) {
        return keyPrefix + "metrics:" + metricName;
    }

    /**
     * 获取 Token 黑名单键
     */
    public String getTokenBlacklistKey(String token) {
        return keyPrefix + "token:blacklist:" + token;
    }
}
