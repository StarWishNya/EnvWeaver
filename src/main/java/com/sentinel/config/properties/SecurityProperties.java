package com.sentinel.config.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * 安全配置属性
 */
@Data
@Validated
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {

    /**
     * 登录尝试配置
     */
    private LoginAttempt loginAttempt = new LoginAttempt();

    /**
     * CORS 配置
     */
    private Cors cors = new Cors();

    /**
     * 白名单路径（不需要认证）
     */
    private List<String> whitelistPaths = List.of(
            "/api/auth/**",
            "/actuator/health",
            "/swagger-ui/**",
            "/v3/api-docs/**"
    );

    /**
     * 登录尝试配置
     */
    @Data
    public static class LoginAttempt {
        /**
         * 最大登录尝试次数
         */
        @Min(value = 1, message = "最大登录尝试次数不能少于1次")
        private Integer maxAttempts = 5;

        /**
         * 账户锁定时间（分钟）
         */
        @Min(value = 1, message = "账户锁定时间不能少于1分钟")
        private Integer lockTimeMinutes = 15;
    }

    /**
     * CORS 配置
     */
    @Data
    public static class Cors {
        /**
         * 允许的源
         */
        @NotEmpty(message = "CORS 允许的源不能为空")
        private List<String> allowedOrigins = List.of("http://localhost:3000", "http://localhost:8080");

        /**
         * 允许的方法
         */
        @NotEmpty(message = "CORS 允许的方法不能为空")
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");

        /**
         * 允许的请求头
         */
        private List<String> allowedHeaders = List.of("*");

        /**
         * 是否允许携带凭证
         */
        private Boolean allowCredentials = true;

        /**
         * 预检请求缓存时间（秒）
         */
        private Long maxAge = 3600L;
    }
}
