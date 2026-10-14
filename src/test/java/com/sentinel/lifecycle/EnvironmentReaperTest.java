package com.sentinel.lifecycle;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.LifecycleProperties;
import com.sentinel.domain.Environment;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.EnvironmentMapper;
import com.sentinel.service.EnvironmentService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 环境回收器单元测试。Mapper 与释放服务均使用 Mockito。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("环境回收器")
class EnvironmentReaperTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-09T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);

    @Mock
    private EnvironmentService environmentService;

    @Mock
    private EnvironmentMapper environmentMapper;

    @Mock
    private TaskLifecycleOrchestrator taskLifecycleOrchestrator;

    private LifecycleProperties properties;
    private EnvironmentReaper reaper;

    @BeforeAll
    static void initLambdaCache() {
        MybatisLambdaCaches.install(Environment.class);
    }

    @BeforeEach
    void setUp() {
        properties = new LifecycleProperties();
        reaper = new EnvironmentReaper(properties, environmentService, environmentMapper,
                taskLifecycleOrchestrator, READY_TIMEOUT, CLOCK);
    }

    @Test
    @DisplayName("释放已过期和 ERROR，跳过 IDLE 与 RELEASING")
    void shouldReleaseExpiredAndErrorButSkipIdleAndReleasing() {
        Environment busy = environment(1L, "BUSY", NOW.minusMinutes(1));
        Environment idle = environment(2L, "IDLE", NOW.minusMinutes(1));
        Environment releasing = environment(3L, "RELEASING", NOW.minusMinutes(1));
        Environment error = environment(4L, "ERROR", NOW.minusSeconds(1));
        when(environmentMapper.selectList(any()))
                .thenReturn(List.of(busy, idle, releasing, error))
                .thenReturn(List.of());

        reaper.reap();

        verify(environmentService).releaseEnvironment(1L);
        verify(environmentService).releaseEnvironment(4L);
        verify(environmentService, never()).releaseEnvironment(2L);
        verify(environmentService, never()).releaseEnvironment(3L);
    }

    @Test
    @DisplayName("过期的 RETAINED 环境会被释放")
    void shouldReleaseExpiredRetainedEnvironment() {
        Environment retained = environment(6L, "RETAINED", NOW.minusSeconds(1));
        when(environmentMapper.selectList(any()))
                .thenReturn(List.of(retained))
                .thenReturn(List.of());

        reaper.reap();

        verify(environmentService).releaseEnvironment(6L);
        ArgumentCaptor<Wrapper<Environment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(environmentMapper, times(2)).selectList(captor.capture());
        assertThat(MybatisLambdaCaches.values(captor.getAllValues().get(0)))
                .contains("IDLE", "RELEASING")
                .doesNotContain("RETAINED");
    }

    @Test
    @DisplayName("单个环境释放失败后继续处理其余环境")
    void shouldContinueAfterReleaseFailure() {
        Environment first = environment(1L, "BUSY", NOW.minusMinutes(2));
        Environment second = environment(2L, "BUSY", NOW.minusMinutes(2));
        when(environmentMapper.selectList(any()))
                .thenReturn(List.of(first, second))
                .thenReturn(List.of());
        doThrow(new BusinessException(ErrorCode.DOCKER_ERROR, "1 个容器释放失败"))
                .when(environmentService).releaseEnvironment(1L);

        reaper.reap();

        verify(environmentService).releaseEnvironment(1L);
        verify(environmentService).releaseEnvironment(2L);
    }

    @Test
    @DisplayName("CREATING 超过两倍就绪超时则释放")
    void shouldReleaseCreatingStuckLongerThanTwiceReadyTimeout() {
        Environment stuck = environment(7L, "CREATING", null);
        stuck.setCreatedAt(NOW.minus(READY_TIMEOUT.multipliedBy(2)).minusSeconds(1));
        when(environmentMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(stuck));

        reaper.reap();

        verify(environmentService).releaseEnvironment(7L);
        ArgumentCaptor<Wrapper<Environment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(environmentMapper, times(2)).selectList(captor.capture());
        assertThat(MybatisLambdaCaches.values(captor.getAllValues().get(0))).contains("IDLE", "RELEASING", NOW);
        LocalDateTime stuckBefore = NOW.minus(READY_TIMEOUT.multipliedBy(2));
        assertThat(MybatisLambdaCaches.values(captor.getAllValues().get(1))).contains("CREATING", stuckBefore);
        verify(environmentMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("回收间隔关闭时不查询环境")
    void shouldNotReapWhenIntervalDisabled() {
        properties.setReaperInterval(Duration.ZERO);

        reaper.reap();

        verify(environmentMapper, never()).selectList(any());
        verify(environmentService, never()).releaseEnvironment(any());
    }

    @Test
    @DisplayName("自动供给关闭时不补扫")
    void shouldNotSweepWhenAutoProvisionDisabled() {
        properties.setAutoProvision(false);

        reaper.sweepStalePendingTasks();

        verify(taskLifecycleOrchestrator, never()).sweepPending();
    }

    @Test
    @DisplayName("自动供给开启时触发补扫")
    void shouldSweepWhenAutoProvisionEnabled() {
        reaper.sweepStalePendingTasks();

        verify(taskLifecycleOrchestrator).sweepPending();
    }

    private static Environment environment(Long id, String status, LocalDateTime expiresAt) {
        Environment environment = new Environment();
        environment.setId(id);
        environment.setStatus(status);
        environment.setExpiresAt(expiresAt);
        environment.setCreatedAt(NOW.minusHours(1));
        return environment;
    }

}
