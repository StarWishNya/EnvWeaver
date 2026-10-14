package com.sentinel.config.properties;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 启动期凭据策略。绑定 {@code sentinel.security}。
 * 管理员口令只来自环境变量 {@code SENTINEL_ADMIN_PASSWORD}，禁止出现在 toString 或日志中。
 */
@Data
@ConfigurationProperties(prefix = "sentinel.security")
public class SentinelSecurityProperties {

    /**
     * 启动检查：fail 拒绝启动，warn 每个问题一条 WARN，off 不检查。
     * 未配置时按 warn。生产配置写 fail，开发与默认配置写 warn，测试配置写 off。
     */
    private String credentialCheck = "warn";

    /**
     * 非空时，启动把用户 admin 的密码设为该值的 BCrypt。
     * 对应环境变量 SENTINEL_ADMIN_PASSWORD，长度至少 12。
     */
    @ToString.Exclude
    private String adminPassword;
}
