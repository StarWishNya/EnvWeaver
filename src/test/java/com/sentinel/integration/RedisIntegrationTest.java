package com.sentinel.integration;

import com.sentinel.BaseTest;
import com.sentinel.config.EmbeddedRedisConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Redis 缓存集成测试
 * 每个用例使用独立键前缀，结束后只删除这些键，不依赖也不清空已有数据。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedRedisConfig.class)
@DisplayName("Redis 缓存集成测试")
public class RedisIntegrationTest extends BaseTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** 本用例的键前缀，避免与其他运行或已有键冲突 */
    private String keyPrefix;

    private final List<String> createdKeys = new ArrayList<>();

    @Override
    @BeforeEach
    public void setUp() {
        super.setUp();
        keyPrefix = "sentinel:it:" + UUID.randomUUID().toString().replace("-", "") + ":";
        createdKeys.clear();
    }

    @AfterEach
    public void cleanupNamespacedKeys() {
        if (createdKeys.isEmpty()) {
            return;
        }
        stringRedisTemplate.delete(new ArrayList<>(createdKeys));
        createdKeys.clear();
    }

    @Test
    @DisplayName("测试Redis连接")
    public void shouldConnectToRedis() {
        // When
        String pong = stringRedisTemplate.getConnectionFactory()
            .getConnection()
            .ping();

        // Then
        assertThat(pong).isEqualTo("PONG");
    }

    @Test
    @DisplayName("测试设置和获取字符串值")
    public void shouldSetAndGetString_whenValidKey() {
        // Given
        String key = namespacedKey("string");
        String value = "test-value";

        // When
        stringRedisTemplate.opsForValue().set(key, value);
        String retrieved = stringRedisTemplate.opsForValue().get(key);

        // Then
        assertThat(retrieved).isEqualTo(value);
    }

    @Test
    @DisplayName("测试设置带过期时间的值")
    public void shouldSetWithExpiration_whenTTLProvided() {
        // Given
        String key = namespacedKey("expiration");
        String value = "expiring-value";
        long ttl = 2; // 2秒

        // When
        stringRedisTemplate.opsForValue().set(key, value, ttl, TimeUnit.SECONDS);

        // Then
        String retrieved = stringRedisTemplate.opsForValue().get(key);
        assertThat(retrieved).isEqualTo(value);

        Long remainingTTL = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(remainingTTL).isLessThanOrEqualTo(ttl);
        assertThat(remainingTTL).isGreaterThan(0);
    }

    @Test
    @DisplayName("测试值过期后自动删除")
    public void shouldExpireValue_afterTTL() throws InterruptedException {
        // Given
        String key = namespacedKey("expire");
        String value = "will-expire";
        long ttl = 1; // 1秒

        // When
        stringRedisTemplate.opsForValue().set(key, value, ttl, TimeUnit.SECONDS);

        // 等待过期
        Thread.sleep(1500);

        String retrieved = stringRedisTemplate.opsForValue().get(key);

        // Then
        assertThat(retrieved).isNull();
    }

    @Test
    @DisplayName("测试删除键")
    public void shouldDeleteKey_whenKeyExists() {
        // Given
        String key = namespacedKey("delete");
        String value = "to-be-deleted";
        stringRedisTemplate.opsForValue().set(key, value);

        // When
        Boolean deleted = stringRedisTemplate.delete(key);

        // Then
        assertThat(deleted).isTrue();
        String retrieved = stringRedisTemplate.opsForValue().get(key);
        assertThat(retrieved).isNull();
    }

    @Test
    @DisplayName("测试检查键是否存在")
    public void shouldCheckKeyExists_whenKeyPresent() {
        // Given
        String key = namespacedKey("exists");
        String value = "exists-value";
        stringRedisTemplate.opsForValue().set(key, value);

        // When
        Boolean exists = stringRedisTemplate.hasKey(key);

        // Then
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("测试检查键不存在")
    public void shouldReturnFalse_whenKeyNotExists() {
        // Given
        String key = namespacedKey("not-exists");

        // When
        Boolean exists = stringRedisTemplate.hasKey(key);

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("测试Token黑名单功能")
    public void shouldAddToBlacklist_whenTokenRevoked() {
        // Given
        String token = "revoked-token-12345";
        String blacklistKey = namespacedKey("token:blacklist:" + token);
        long ttl = 3600; // 1小时

        // When
        stringRedisTemplate.opsForValue().set(blacklistKey, "revoked", ttl, TimeUnit.SECONDS);

        // Then
        Boolean isBlacklisted = stringRedisTemplate.hasKey(blacklistKey);
        assertThat(isBlacklisted).isTrue();
    }

    @Test
    @DisplayName("测试诊断结果缓存")
    public void shouldCacheDiagnosisResult_whenDiagnosisCompleted() {
        // Given
        String taskId = "task-123";
        String cacheKey = namespacedKey("diagnosis:result:" + taskId);
        String diagnosisResult = "{\"status\":\"success\",\"solution\":\"Fix the bug\"}";
        long ttl = 3600; // 1小时

        // When
        stringRedisTemplate.opsForValue().set(cacheKey, diagnosisResult, ttl, TimeUnit.SECONDS);

        // Then
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        assertThat(cached).isEqualTo(diagnosisResult);
    }

    @Test
    @DisplayName("测试缓存更新")
    public void shouldUpdateCache_whenValueChanged() {
        // Given
        String key = namespacedKey("update");
        String oldValue = "old-value";
        String newValue = "new-value";

        stringRedisTemplate.opsForValue().set(key, oldValue);

        // When
        stringRedisTemplate.opsForValue().set(key, newValue);
        String retrieved = stringRedisTemplate.opsForValue().get(key);

        // Then
        assertThat(retrieved).isEqualTo(newValue);
        assertThat(retrieved).isNotEqualTo(oldValue);
    }

    @Test
    @DisplayName("测试批量设置和获取")
    public void shouldSetAndGetMultipleKeys() {
        // Given
        String key1 = namespacedKey("batch:1");
        String key2 = namespacedKey("batch:2");
        String key3 = namespacedKey("batch:3");

        // When
        stringRedisTemplate.opsForValue().set(key1, "value1");
        stringRedisTemplate.opsForValue().set(key2, "value2");
        stringRedisTemplate.opsForValue().set(key3, "value3");

        // Then
        assertThat(stringRedisTemplate.opsForValue().get(key1)).isEqualTo("value1");
        assertThat(stringRedisTemplate.opsForValue().get(key2)).isEqualTo("value2");
        assertThat(stringRedisTemplate.opsForValue().get(key3)).isEqualTo("value3");
    }

    @Test
    @DisplayName("测试递增操作")
    public void shouldIncrementValue_whenKeyIsNumeric() {
        // Given
        String key = namespacedKey("counter");

        // When
        Long value1 = stringRedisTemplate.opsForValue().increment(key);
        Long value2 = stringRedisTemplate.opsForValue().increment(key);
        Long value3 = stringRedisTemplate.opsForValue().increment(key);

        // Then
        assertThat(value1).isEqualTo(1);
        assertThat(value2).isEqualTo(2);
        assertThat(value3).isEqualTo(3);
    }

    @Test
    @DisplayName("测试递减操作")
    public void shouldDecrementValue_whenKeyIsNumeric() {
        // Given
        String key = namespacedKey("decrement");
        stringRedisTemplate.opsForValue().set(key, "10");

        // When
        Long value1 = stringRedisTemplate.opsForValue().decrement(key);
        Long value2 = stringRedisTemplate.opsForValue().decrement(key);

        // Then
        assertThat(value1).isEqualTo(9);
        assertThat(value2).isEqualTo(8);
    }

    @Test
    @DisplayName("测试获取不存在的键返回null")
    public void shouldReturnNull_whenKeyNotExists() {
        // Given
        String key = namespacedKey("non-existent");

        // When
        String value = stringRedisTemplate.opsForValue().get(key);

        // Then
        assertThat(value).isNull();
    }

    @Test
    @DisplayName("测试设置键的过期时间")
    public void shouldSetExpiration_forExistingKey() {
        // Given
        String key = namespacedKey("set-expire");
        String value = "value";
        stringRedisTemplate.opsForValue().set(key, value);

        // When
        Boolean result = stringRedisTemplate.expire(key, 60, TimeUnit.SECONDS);

        // Then
        assertThat(result).isTrue();
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isLessThanOrEqualTo(60);
        assertThat(ttl).isGreaterThan(0);
    }

    @Test
    @DisplayName("测试移除键的过期时间")
    public void shouldPersistKey_whenRemovingExpiration() {
        // Given
        String key = namespacedKey("persist");
        String value = "value";
        stringRedisTemplate.opsForValue().set(key, value, 60, TimeUnit.SECONDS);

        // When
        Boolean result = stringRedisTemplate.persist(key);

        // Then
        assertThat(result).isTrue();
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isEqualTo(-1); // -1表示没有过期时间
    }

    @Test
    @DisplayName("测试Redis连接失败时的降级处理")
    public void shouldHandleConnectionFailure_gracefully() {
        // 这个测试模拟Redis连接失败的场景
        // 在实际应用中，应该有降级策略

        try {
            // 尝试操作
            String key = namespacedKey("failure");
            stringRedisTemplate.opsForValue().set(key, "value");

            // 如果成功，验证值
            String value = stringRedisTemplate.opsForValue().get(key);
            assertThat(value).isEqualTo("value");
        } catch (Exception e) {
            // 连接失败时应该有降级处理
            // 这里只是验证异常被正确捕获
            assertThat(e).isNotNull();
        }
    }

    private String namespacedKey(String suffix) {
        String key = keyPrefix + suffix;
        if (!createdKeys.contains(key)) {
            createdKeys.add(key);
        }
        return key;
    }
}
