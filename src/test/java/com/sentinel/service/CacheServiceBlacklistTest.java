package com.sentinel.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sentinel.config.properties.CacheProperties;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Token 黑名单：写入调用方 TTL，读取时 Redis 故障失败关闭。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CacheService Token 黑名单")
class CacheServiceBlacklistTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private CacheProperties cacheProperties;

    @InjectMocks
    private CacheService cacheService;

    @Test
    @DisplayName("Redis 连接失败时 isTokenBlacklisted 视为已拉黑")
    void isTokenBlacklistedFailsClosedOnRedisConnectionFailure() {
        String token = "raw-secret-token-value";
        when(cacheProperties.getTokenBlacklistKey(token)).thenReturn("sentinel:token:blacklist:" + token);
        when(redisTemplate.hasKey("sentinel:token:blacklist:" + token))
                .thenThrow(new RedisConnectionFailureException("down"));

        Logger logger = (Logger) LoggerFactory.getLogger(CacheService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(cacheService.isTokenBlacklisted(token)).isTrue();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message).doesNotContain(token));
    }

    @Test
    @DisplayName("exists 在 Redis 连接失败时仍然返回 false")
    void existsStillFailsOpenOnRedisConnectionFailure() {
        when(redisTemplate.hasKey("plain-key")).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cacheService.exists("plain-key")).isFalse();
    }

    @Test
    @DisplayName("指定 TTL 的黑名单写入使用该秒数")
    void addTokenToBlacklistUsesProvidedTtl() {
        when(cacheProperties.getTokenBlacklistKey("tok")).thenReturn("sentinel:token:blacklist:tok");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        cacheService.addTokenToBlacklist("tok", 5400L);

        verify(valueOperations).set(eq("sentinel:token:blacklist:tok"), eq(Boolean.TRUE), eq(5400L), eq(TimeUnit.SECONDS));
    }
}
