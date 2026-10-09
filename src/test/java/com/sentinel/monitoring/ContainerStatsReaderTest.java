package com.sentinel.monitoring;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.CpuUsageConfig;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StatsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 容器统计读取。DockerClient 全部使用 Mockito，不连接守护进程。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("容器资源采样")
class ContainerStatsReaderTest {

    private static final long MIB = 1024L * 1024L;

    @Mock
    private DockerClient dockerClient;

    private ResourceUsageProperties properties;
    private ContainerStatsReader reader;

    @BeforeEach
    void setUp() {
        properties = new ResourceUsageProperties();
        reader = new ContainerStatsReader(dockerClient, properties);
    }

    @Test
    @DisplayName("CPU 按增量乘在线核数，内存减去 inactive_file")
    void calculatesCpuCoresAndSubtractsInactiveFile() {
        deliver("c1", statistics(
                cpu(200, 1000, 4L, null),
                pre(100, 500L),
                10 * MIB,
                2 * MIB,
                null));

        Optional<ContainerStatsReader.Sample> sample = reader.read("c1");

        assertThat(sample).isPresent();
        assertThat(sample.get().cpuCores()).isEqualTo((100d / 500d) * 4d);
        assertThat(sample.get().memoryBytes()).isEqualTo(8 * MIB);
        verify(dockerClient).statsCmd("c1");
    }

    @Test
    @DisplayName("没有 inactive_file 时减去 cache")
    void subtractsCacheWhenInactiveFileMissing() {
        deliver("c1", statistics(
                cpu(200, 1000, 1L, null),
                pre(100, 500L),
                5 * MIB,
                null,
                MIB));

        Optional<ContainerStatsReader.Sample> sample = reader.read("c1");

        assertThat(sample).get().extracting(ContainerStatsReader.Sample::memoryBytes).isEqualTo(4 * MIB);
    }

    @Test
    @DisplayName("inactive_file 和 cache 同时存在时用 inactive_file")
    void prefersInactiveFileOverCache() {
        deliver("c1", statistics(
                cpu(200, 1000, 1L, null),
                pre(100, 500L),
                10 * MIB,
                2 * MIB,
                6 * MIB));

        Optional<ContainerStatsReader.Sample> sample = reader.read("c1");

        assertThat(sample).get().extracting(ContainerStatsReader.Sample::memoryBytes).isEqualTo(8 * MIB);
    }

    @Test
    @DisplayName("缓存大于 usage 时内存不为负")
    void clampsMemoryAtZeroWhenCacheExceedsUsage() {
        deliver("c1", statistics(
                cpu(200, 1000, 1L, null),
                pre(100, 500L),
                MIB,
                null,
                3 * MIB));

        Optional<ContainerStatsReader.Sample> sample = reader.read("c1");

        assertThat(sample).get().extracting(ContainerStatsReader.Sample::memoryBytes).isEqualTo(0L);
    }

    @Test
    @DisplayName("负的 inactive_file 不当成负缓存")
    void ignoresNegativeCache() {
        deliver("c1", statistics(
                cpu(200, 1000, 1L, null),
                pre(100, 500L),
                100L,
                -50L,
                null));

        Optional<ContainerStatsReader.Sample> sample = reader.read("c1");

        assertThat(sample).get().extracting(ContainerStatsReader.Sample::memoryBytes).isEqualTo(100L);
    }

    @Test
    @DisplayName("precpu 全 0 时 CPU 记 0，内存仍保留")
    void returnsZeroCpuWhenPrecpuIsZero() {
        deliver("c1", statistics(null, pre(0L, null), 4 * MIB, null, null));

        Optional<ContainerStatsReader.Sample> sample = reader.read("c1");

        assertThat(sample).isPresent();
        assertThat(sample.get().cpuCores()).isEqualTo(0.0);
        assertThat(sample.get().memoryBytes()).isEqualTo(4 * MIB);
    }

    @Test
    @DisplayName("没有 precpu 时 CPU 记 0，而不是拿累计值硬算")
    void returnsZeroCpuWhenPrecpuMissing() {
        deliver("c1", statistics(null, null, 4 * MIB, null, null));

        Optional<ContainerStatsReader.Sample> sample = reader.read("c1");

        assertThat(sample).isPresent();
        assertThat(sample.get().cpuCores()).isEqualTo(0.0);
        assertThat(sample.get().memoryBytes()).isEqualTo(4 * MIB);
    }

    @Test
    @DisplayName("上一拍有效但增量为 0 时 CPU 为 0")
    void returnsZeroCpuWhenDeltaIsZero() {
        deliver("c1", statistics(
                cpu(10, 10, null, null),
                pre(10, 10L),
                2 * MIB,
                null,
                null));

        Optional<ContainerStatsReader.Sample> sample = reader.read("c1");

        assertThat(sample).get().extracting(ContainerStatsReader.Sample::cpuCores).isEqualTo(0.0);
        assertThat(sample.get().memoryBytes()).isEqualTo(2 * MIB);
    }

    @Test
    @DisplayName("online_cpus 缺失时用 percpuUsage 的个数")
    void usesPercpuCountWhenOnlineCpusMissing() {
        deliver("c1", statistics(
                cpu(150, 150, null, List.of(1L, 1L, 1L, 1L)),
                pre(50, 50L),
                MIB,
                null,
                null));

        Optional<ContainerStatsReader.Sample> sample = reader.read("c1");

        assertThat(sample).get().extracting(ContainerStatsReader.Sample::cpuCores).isEqualTo(4.0);
    }

    @Test
    @Timeout(3)
    @DisplayName("统计超时返回空并关闭回调")
    void returnsEmptyWhenStatsTimeOut() throws Exception {
        properties.setReadTimeout(Duration.ofMillis(100));
        Closeable stream = mock(Closeable.class);
        StatsCmd command = hang("hung", stream);

        long started = System.nanoTime();
        Optional<ContainerStatsReader.Sample> sample = reader.read("hung");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(sample).isEmpty();
        assertThat(elapsedMs).isGreaterThanOrEqualTo(50L);
        assertThat(elapsedMs).isLessThan(2_000L);
        verify(command).withNoStream(true);
        verify(stream).close();
    }

    @Test
    @DisplayName("容器不存在时返回空")
    void returnsEmptyWhenContainerMissing() {
        StatsCmd command = command("gone");
        when(command.exec(any(ResultCallback.class))).thenThrow(new NotFoundException("no such container"));

        assertThat(reader.read("gone")).isEmpty();
        verify(command).withNoStream(true);
    }

    @Test
    @DisplayName("统计回调报错时返回空")
    void returnsEmptyWhenCallbackErrors() {
        StatsCmd command = command("stopped");
        doAnswer(invocation -> {
            ResultCallback<Statistics> callback = invocation.getArgument(0);
            callback.onError(new NotFoundException("container is not running"));
            return callback;
        }).when(command).exec(any(ResultCallback.class));

        assertThat(reader.read("stopped")).isEmpty();
    }

    @Test
    @DisplayName("没有统计帧时返回空")
    void returnsEmptyWhenNoStatistics() {
        deliver("empty", null);

        assertThat(reader.read("empty")).isEmpty();
    }

    @Test
    @DisplayName("容器 ID 为空时不访问 Docker")
    void ignoresBlankContainerId() {
        assertThat(reader.read(null)).isEmpty();
        assertThat(reader.read("  ")).isEmpty();
        verifyNoInteractions(dockerClient);
    }

    @SuppressWarnings("unchecked")
    private void deliver(String containerId, Statistics statistics) {
        StatsCmd command = command(containerId);
        Closeable stream = mock(Closeable.class);
        doAnswer(deliverAnswer(stream, statistics)).when(command).exec(any(ResultCallback.class));
    }

    @SuppressWarnings("unchecked")
    private StatsCmd hang(String containerId, Closeable stream) {
        StatsCmd command = command(containerId);
        doAnswer(invocation -> {
            ResultCallback<Statistics> callback = invocation.getArgument(0);
            callback.onStart(stream);
            return callback;
        }).when(command).exec(any(ResultCallback.class));
        return command;
    }

    private StatsCmd command(String containerId) {
        StatsCmd command = mock(StatsCmd.class);
        when(dockerClient.statsCmd(containerId)).thenReturn(command);
        when(command.withNoStream(true)).thenReturn(command);
        return command;
    }

    private static Answer<Object> deliverAnswer(Closeable stream, Statistics statistics) {
        return invocation -> {
            ResultCallback<Statistics> callback = invocation.getArgument(0);
            callback.onStart(stream);
            if (statistics != null) {
                callback.onNext(statistics);
            }
            callback.onComplete();
            return callback;
        };
    }

    private Statistics statistics(CpuStatsConfig cpu, CpuStatsConfig pre, long usage, Long inactive, Long cache) {
        Statistics statistics = mock(Statistics.class);
        if (cpu != null) {
            when(statistics.getCpuStats()).thenReturn(cpu);
        }
        if (pre != null) {
            when(statistics.getPreCpuStats()).thenReturn(pre);
        }
        MemoryStatsConfig memory = mock(MemoryStatsConfig.class);
        when(statistics.getMemoryStats()).thenReturn(memory);
        when(memory.getUsage()).thenReturn(usage);
        StatsConfig details = mock(StatsConfig.class);
        when(memory.getStats()).thenReturn(details);
        // Mockito 对 Long 的默认值是 0，不是 null。0 表示这项缓存存在且为 0。
        when(details.getInactiveFile()).thenReturn(inactive);
        when(details.getCache()).thenReturn(cache);
        return statistics;
    }

    private CpuStatsConfig cpu(long total, long system, Long online, List<Long> percpu) {
        CpuStatsConfig config = mock(CpuStatsConfig.class);
        CpuUsageConfig usage = mock(CpuUsageConfig.class);
        when(config.getCpuUsage()).thenReturn(usage);
        when(usage.getTotalUsage()).thenReturn(total);
        when(config.getSystemCpuUsage()).thenReturn(system);
        if (online != null) {
            when(config.getOnlineCpus()).thenReturn(online);
        }
        if (percpu != null) {
            when(usage.getPercpuUsage()).thenReturn(percpu);
        }
        return config;
    }

    /**
     * total 不大于 0 时读取逻辑不会再看 system，避免多余的 stub。
     */
    private CpuStatsConfig pre(long total, Long system) {
        CpuStatsConfig config = mock(CpuStatsConfig.class);
        CpuUsageConfig usage = mock(CpuUsageConfig.class);
        when(config.getCpuUsage()).thenReturn(usage);
        when(usage.getTotalUsage()).thenReturn(total);
        if (total > 0) {
            when(config.getSystemCpuUsage()).thenReturn(system);
        }
        return config;
    }
}
