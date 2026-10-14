package com.sentinel.monitoring;

import com.sentinel.domain.Container;
import com.sentinel.domain.Environment;
import com.sentinel.mapper.ContainerMapper;
import com.sentinel.mapper.EnvironmentMapper;
import com.sentinel.monitoring.ContainerStatsReader.Sample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 资源采集器。容器读取使用 mock，不连接 Docker。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("资源采集器")
class ResourceUsageCollectorTest {

    private static final long MIB = 1024L * 1024L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T01:00:00Z"), ZoneOffset.UTC);
    private static final String SAMPLED_AT = "2026-10-09T01:00:00";

    @Mock
    private EnvironmentMapper environmentMapper;

    @Mock
    private ContainerMapper containerMapper;

    @Mock
    private ContainerStatsReader statsReader;

    private ResourceUsageProperties properties;
    private ResourceUsageCollector collector;

    @BeforeEach
    void setUp() {
        properties = new ResourceUsageProperties();
        collector = newCollector(ResourceUsageCollector.SAME_THREAD);
    }

    @Test
    @Timeout(5)
    @DisplayName("两个容器的 CPU 与内存相加")
    void sumsTwoContainers() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            ResourceUsageCollector parallel = newCollector(pool);
            Environment environment = environment(5L, "READY", null);
            when(environmentMapper.selectByStatuses(anyList(), anyInt())).thenReturn(List.of(environment));
            when(environmentMapper.selectIdleWithResourceUsage(anyInt())).thenReturn(List.of());
            when(containerMapper.findRunningByEnvironmentId(5L)).thenReturn(List.of(
                    running("c1"), running("c2")));
            when(statsReader.read("c1")).thenReturn(Optional.of(new Sample(0.25, 100 * MIB)));
            when(statsReader.read("c2")).thenReturn(Optional.of(new Sample(0.50, 50 * MIB)));

            parallel.collect();
        } finally {
            pool.shutdownNow();
        }

        verify(environmentMapper).updateResourceUsage(5L,
                "{\"cpu\":0.75,\"memory\":150,\"sampledAt\":\"" + SAMPLED_AT + "\"}");
        verify(environmentMapper, never()).updateById(org.mockito.ArgumentMatchers.<Environment>any());
    }

    @Test
    @DisplayName("CPU 保留三位小数，内存按 MiB 四舍五入")
    void roundsCpuAndMemory() {
        assertThat(ResourceUsageCollector.roundCpu(0.1114 + 0.1114)).isEqualTo(0.223);
        assertThat(ResourceUsageCollector.roundCpu(1.2345)).isEqualTo(1.235);
        assertThat(ResourceUsageCollector.roundCpu(0.0)).isEqualTo(0.0);
        assertThat(ResourceUsageCollector.memoryMb(1572864L)).isEqualTo(2);
        assertThat(ResourceUsageCollector.memoryMb(0L)).isEqualTo(0);

        when(environmentMapper.selectByStatuses(anyList(), anyInt()))
                .thenReturn(List.of(environment(8L, "BUSY", null)));
        when(environmentMapper.selectIdleWithResourceUsage(anyInt())).thenReturn(List.of());
        when(containerMapper.findRunningByEnvironmentId(8L)).thenReturn(List.of(running("c1"), running("c2")));
        when(statsReader.read("c1")).thenReturn(Optional.of(new Sample(0.1114, 419430L)));
        when(statsReader.read("c2")).thenReturn(Optional.of(new Sample(0.1114, 419430L)));

        collector.collect();

        verify(environmentMapper).updateResourceUsage(8L,
                "{\"cpu\":0.223,\"memory\":1,\"sampledAt\":\"" + SAMPLED_AT + "\"}");
    }

    @Test
    @DisplayName("容器统计全部失败时保留原值")
    void keepsPreviousWhenEveryReadFails() {
        Environment environment = environment(3L, "BUSY", "{\"cpu\":1.5,\"memory\":32}");
        when(environmentMapper.selectByStatuses(anyList(), anyInt())).thenReturn(List.of(environment));
        when(environmentMapper.selectIdleWithResourceUsage(anyInt())).thenReturn(List.of());
        when(containerMapper.findRunningByEnvironmentId(3L)).thenReturn(List.of(running("c1"), running("c2")));
        when(statsReader.read("c1")).thenReturn(Optional.empty());
        when(statsReader.read("c2")).thenReturn(Optional.empty());

        collector.collect();

        verify(environmentMapper, never()).updateResourceUsage(anyLong(), nullable(String.class));
        assertThat(environment.getResourceUsage()).isEqualTo("{\"cpu\":1.5,\"memory\":32}");
        assertThat(environment.getStatus()).isEqualTo("BUSY");
    }

    @Test
    @DisplayName("一个容器失败时仍写入其余容器的合计")
    void writesPartialSumWhenOneContainerFails() {
        when(environmentMapper.selectByStatuses(anyList(), anyInt()))
                .thenReturn(List.of(environment(6L, "READY", "{\"cpu\":9.0,\"memory\":9}")));
        when(environmentMapper.selectIdleWithResourceUsage(anyInt())).thenReturn(List.of());
        when(containerMapper.findRunningByEnvironmentId(6L)).thenReturn(List.of(running("c1"), running("c2")));
        when(statsReader.read("c1")).thenReturn(Optional.of(new Sample(0.25, 100 * MIB)));
        when(statsReader.read("c2")).thenThrow(new IllegalStateException("stats failed"));

        collector.collect();

        verify(environmentMapper).updateResourceUsage(6L,
                "{\"cpu\":0.25,\"memory\":100,\"sampledAt\":\"" + SAMPLED_AT + "\"}");
    }

    @Test
    @DisplayName("关闭采集时不查库也不读容器")
    void skipsWhenDisabled() {
        properties.setEnabled(false);

        collector.collect();

        verifyNoInteractions(environmentMapper, containerMapper, statsReader);
    }

    @Test
    @DisplayName("空闲环境的资源使用被清空")
    void clearsIdleResourceUsage() {
        when(environmentMapper.selectByStatuses(anyList(), anyInt())).thenReturn(List.of());
        when(environmentMapper.selectIdleWithResourceUsage(anyInt()))
                .thenReturn(List.of(environment(9L, "IDLE", "{\"cpu\":1.0,\"memory\":10}")));

        collector.collect();

        verify(environmentMapper).updateResourceUsage(9L, null);
        verify(environmentMapper, never()).updateResourceUsage(eq(9L), org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(statsReader, containerMapper);
    }

    @Test
    @DisplayName("单个环境失败后继续处理其余环境")
    void continuesAfterEnvironmentFailure() {
        when(environmentMapper.selectByStatuses(anyList(), anyInt())).thenReturn(List.of(
                environment(1L, "READY", null),
                environment(2L, "RETAINED", null)));
        when(environmentMapper.selectIdleWithResourceUsage(anyInt())).thenReturn(List.of());
        when(containerMapper.findRunningByEnvironmentId(1L)).thenThrow(new RuntimeException("db down"));
        when(containerMapper.findRunningByEnvironmentId(2L)).thenReturn(List.of(running("ok")));
        when(statsReader.read("ok")).thenReturn(Optional.of(new Sample(0.5, 2 * MIB)));

        collector.collect();

        verify(environmentMapper, never()).updateResourceUsage(eq(1L), nullable(String.class));
        verify(environmentMapper).updateResourceUsage(2L,
                "{\"cpu\":0.5,\"memory\":2,\"sampledAt\":\"" + SAMPLED_AT + "\"}");
    }

    @Test
    @DisplayName("没有运行中的容器时写成 0")
    void writesZeroWhenNoRunningContainers() {
        when(environmentMapper.selectByStatuses(anyList(), anyInt()))
                .thenReturn(List.of(environment(4L, "RETAINED", "{\"cpu\":3.0,\"memory\":9}")));
        when(environmentMapper.selectIdleWithResourceUsage(anyInt())).thenReturn(List.of());
        when(containerMapper.findRunningByEnvironmentId(4L)).thenReturn(List.of());

        collector.collect();

        verify(environmentMapper).updateResourceUsage(4L,
                "{\"cpu\":0.0,\"memory\":0,\"sampledAt\":\"" + SAMPLED_AT + "\"}");
        verifyNoInteractions(statsReader);
    }

    @Test
    @DisplayName("容器查询失败时保留原值")
    void keepsPreviousWhenContainerQueryFails() {
        Environment environment = environment(7L, "READY", "{\"cpu\":1.0,\"memory\":1}");
        when(environmentMapper.selectByStatuses(anyList(), anyInt())).thenReturn(List.of(environment));
        when(environmentMapper.selectIdleWithResourceUsage(anyInt())).thenReturn(List.of());
        when(containerMapper.findRunningByEnvironmentId(7L)).thenReturn(null);

        collector.collect();

        verify(environmentMapper, never()).updateResourceUsage(anyLong(), nullable(String.class));
        assertThat(environment.getStatus()).isEqualTo("READY");
    }

    @Test
    @DisplayName("只采样 READY、BUSY、RETAINED，并限制每轮数量")
    void selectsOnlyActiveStatusesWithLimit() {
        properties.setMaxEnvironmentsPerPass(15);
        when(environmentMapper.selectByStatuses(ResourceUsageCollector.SAMPLED_STATUSES, 15)).thenReturn(List.of());
        when(environmentMapper.selectIdleWithResourceUsage(15)).thenReturn(List.of());

        collector.collect();

        assertThat(ResourceUsageCollector.SAMPLED_STATUSES).containsExactly("READY", "BUSY", "RETAINED");
        verify(environmentMapper).selectByStatuses(ResourceUsageCollector.SAMPLED_STATUSES, 15);
        verify(environmentMapper).selectIdleWithResourceUsage(15);
        verifyNoInteractions(statsReader, containerMapper);
    }

    @Test
    @DisplayName("调度间隔用毫秒 SpEL，首次执行延后一个间隔")
    void schedulesWithIntervalMillis() throws Exception {
        Method collect = ResourceUsageCollector.class.getMethod("collect");
        Scheduled scheduled = collect.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("#{@resourceUsageProperties.interval.toMillis()}");
        assertThat(scheduled.initialDelayString()).isEqualTo("#{@resourceUsageProperties.interval.toMillis()}");
        assertThat(properties.getInterval().toMillis()).isEqualTo(30_000L);
    }

    private ResourceUsageCollector newCollector(java.util.concurrent.Executor containerReads) {
        return new ResourceUsageCollector(properties, environmentMapper, containerMapper, statsReader,
                containerReads, CLOCK, false);
    }

    private static Environment environment(Long id, String status, String resourceUsage) {
        Environment environment = new Environment();
        environment.setId(id);
        environment.setStatus(status);
        environment.setResourceUsage(resourceUsage);
        return environment;
    }

    private static Container running(String containerId) {
        Container container = new Container();
        container.setContainerId(containerId);
        container.setStatus("RUNNING");
        return container;
    }
}
