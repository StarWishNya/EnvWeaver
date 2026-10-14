package com.sentinel.config;

import com.sentinel.config.properties.ProvisioningProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册环境供给配置。
 */
@Configuration
@EnableConfigurationProperties(ProvisioningProperties.class)
public class ProvisioningConfig {
}
