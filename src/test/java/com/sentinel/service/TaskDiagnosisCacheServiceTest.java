package com.sentinel.service;

import com.sentinel.config.properties.CacheProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务诊断状态使用诊断 TTL，键与日志哈希缓存分开。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("任务诊断 Redis 状态")
class TaskDiagnosisCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private CacheProperties cacheProperties;

    @InjectMocks
    private CacheService cacheService;

    @Test
    @DisplayName("写入任务诊断状态时使用 diagnosis TTL")
    void setTaskDiagnosisUsesDiagnosisTtl() {
        when(cacheProperties.getDiagnosisKey("task:7")).thenReturn("sentinel:diagnosis:task:7");
        when(cacheProperties.getDiagnosisTtl()).thenReturn(3600L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        cacheService.setTaskDiagnosis(7L, "state");

        verify(valueOperations).set("sentinel:diagnosis:task:7", "state", 3600L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("按任务 ID 读取诊断状态")
    void getTaskDiagnosisReadsTaskKey() {
        when(cacheProperties.getDiagnosisKey("task:7")).thenReturn("sentinel:diagnosis:task:7");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("sentinel:diagnosis:task:7")).thenReturn("state");

        assertThat(cacheService.getTaskDiagnosis(7L)).isEqualTo("state");
    }
}
