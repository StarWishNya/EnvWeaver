package com.sentinel;

import com.sentinel.config.properties.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sentinel 智能测试环境管理系统主启动类
 * 
 * @author Sentinel Team
 * @version 1.0.0
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({
        JwtProperties.class,
        SecurityProperties.class,
        CacheProperties.class,
        AIProperties.class,
        DockerProperties.class
})
public class SentinelApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentinelApplication.class, args);
    }
}
