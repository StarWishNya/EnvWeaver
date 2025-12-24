package com.sentinel.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Profile;
import redis.embedded.RedisServer;

import java.io.IOException;

/**
 * 嵌入式 Redis 配置
 * 用于测试环境
 */
@TestConfiguration
@Profile("test")
public class EmbeddedRedisConfig {

    private RedisServer redisServer;

    @PostConstruct
    public void startRedis() throws IOException {
        try {
            redisServer = new RedisServer(6370);
            redisServer.start();
        } catch (Exception e) {
            // Redis 可能已经在运行，忽略错误
            System.err.println("Failed to start embedded Redis: " + e.getMessage());
        }
    }

    @PreDestroy
    public void stopRedis() {
        if (redisServer != null && redisServer.isActive()) {
            redisServer.stop();
        }
    }
}
