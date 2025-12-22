package com.sentinel.config.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 配置属性
 */
@Data
@Validated
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * JWT 密钥，用于签名和验证 Token
     * 生产环境必须使用环境变量配置
     */
    @NotBlank(message = "JWT 密钥不能为空")
    private String secret;

    /**
     * 访问令牌过期时间（毫秒）
     * 默认：24小时
     */
    @Min(value = 60000, message = "访问令牌过期时间不能少于1分钟")
    private Long expiration = 86400000L;

    /**
     * 刷新令牌过期时间（毫秒）
     * 默认：7天
     */
    @Min(value = 3600000, message = "刷新令牌过期时间不能少于1小时")
    private Long refreshExpiration = 604800000L;

    /**
     * Token 发行者
     */
    private String issuer = "sentinel";

    /**
     * Token 受众
     */
    private String audience = "sentinel-users";

    /**
     * Token 请求头名称
     */
    private String header = "Authorization";

    /**
     * Token 前缀
     */
    private String tokenPrefix = "Bearer ";
}
