package com.sentinel.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * 用 Redis PING 检查连接。
 */
@Slf4j
@Component
public class RedisHealthChecker implements ComponentHealthChecker {

    private final RedisConnectionFactory connectionFactory;

    public RedisHealthChecker(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public String name() {
        return REDIS;
    }

    @Override
    public boolean up() {
        RedisConnection connection = null;
        try {
            connection = connectionFactory.getConnection();
            if (connection == null) {
                return false;
            }
            String pong = connection.ping();
            return pong != null && "PONG".equalsIgnoreCase(pong.trim());
        } catch (Exception e) {
            log.warn("Redis health check failed: {}", e.getClass().getSimpleName());
            return false;
        } finally {
            close(connection);
        }
    }

    private static void close(RedisConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception e) {
            log.warn("Redis connection close failed: {}", e.getClass().getSimpleName());
        }
    }
}
