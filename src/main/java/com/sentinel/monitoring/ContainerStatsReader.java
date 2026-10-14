package com.sentinel.monitoring;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.CpuUsageConfig;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StatsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 读取一次容器资源采样。
 * <p>
 * 使用 {@code statsCmd(id).withNoStream(true)} 拿一帧统计。非流式读取经常没有上一拍：
 * precpuStats 为空或全 0。这时用当前累计值做差会得到毫无意义的 CPU，因此记 0.0，内存仍按本拍计算。
 * 有上一拍时：cores = (cpuTotalDelta / systemCpuDelta) * onlineCpus。
 * onlineCpus 缺失或不大于 0 时，退回本拍 percpuUsage 的个数（旧统计没有 online_cpus）。
 * 内存 = usage - inactive_file（cgroup v2）；没有 inactive_file 时减去 cache（cgroup v1）。结果不会为负。
 * 容器不存在、已停止，或在超时内没有结果时返回 empty。
 *
 * @author Sentinel Team
 */
@Slf4j
@Component
public class ContainerStatsReader {

    static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

    private final DockerClient dockerClient;
    private final ResourceUsageProperties properties;

    public ContainerStatsReader(DockerClient dockerClient, ResourceUsageProperties properties) {
        this.dockerClient = dockerClient;
        this.properties = properties;
    }

    /**
     * 读取一个容器的 CPU 核数和内存字节数。
     *
     * @param containerId Docker 容器 ID
     * @return 采样；读不到时为 empty
     */
    public Optional<Sample> read(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return Optional.empty();
        }
        Duration timeout = readTimeout();
        long waitMs = Math.max(timeout.toMillis(), 0L);
        StatsCallback callback = new StatsCallback();
        StatsCmd command = null;
        try {
            command = dockerClient.statsCmd(containerId).withNoStream(true);
            command.exec(callback);
            boolean completed = callback.awaitCompletion(waitMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                log.warn("读取容器统计超时: containerId={}, timeoutMs={}", containerId, waitMs);
                return Optional.empty();
            }
            Statistics statistics = callback.first();
            if (statistics == null) {
                log.debug("容器统计为空: containerId={}", containerId);
                return Optional.empty();
            }
            return Optional.of(new Sample(cpuCores(statistics), memoryBytes(statistics)));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("读取容器统计被中断: containerId={}", containerId);
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.debug("读取容器统计失败，容器可能已停止或不存在: containerId={}, reason={}",
                    containerId, ex.getMessage());
            return Optional.empty();
        } finally {
            closeQuietly(callback);
            closeQuietly(command);
        }
    }

    /**
     * 没有可用的上一拍时返回 0，避免把累计值当成增量。
     */
    static double cpuCores(Statistics statistics) {
        if (statistics == null || !hasBaseline(statistics.getPreCpuStats())) {
            return 0.0;
        }
        CpuStatsConfig cpu = statistics.getCpuStats();
        CpuStatsConfig pre = statistics.getPreCpuStats();
        Long total = usageTotal(cpu);
        Long preTotal = usageTotal(pre);
        Long system = cpu == null ? null : cpu.getSystemCpuUsage();
        Long preSystem = pre.getSystemCpuUsage();
        if (total == null || preTotal == null || system == null || preSystem == null) {
            return 0.0;
        }
        long cpuDelta = total - preTotal;
        long systemDelta = system - preSystem;
        if (cpuDelta <= 0 || systemDelta <= 0) {
            return 0.0;
        }
        long online = onlineCpus(cpu);
        if (online <= 0) {
            return 0.0;
        }
        return (cpuDelta / (double) systemDelta) * online;
    }

    /**
     * usage 含缓存。cgroup v2 的缓存在 inactive_file，cgroup v1 在 cache。两者都有时用 inactive_file。
     */
    static long memoryBytes(Statistics statistics) {
        MemoryStatsConfig memory = statistics == null ? null : statistics.getMemoryStats();
        if (memory == null || memory.getUsage() == null) {
            return 0L;
        }
        long usage = memory.getUsage();
        long cache = cacheBytes(memory.getStats());
        long used = usage - cache;
        return Math.max(used, 0L);
    }

    private Duration readTimeout() {
        if (properties == null || properties.getReadTimeout() == null || properties.getReadTimeout().isNegative()) {
            return DEFAULT_READ_TIMEOUT;
        }
        return properties.getReadTimeout();
    }

    private static boolean hasBaseline(CpuStatsConfig pre) {
        if (pre == null || pre.getCpuUsage() == null) {
            return false;
        }
        Long total = pre.getCpuUsage().getTotalUsage();
        Long system = pre.getSystemCpuUsage();
        return total != null && total > 0 && system != null && system > 0;
    }

    private static Long usageTotal(CpuStatsConfig cpu) {
        if (cpu == null || cpu.getCpuUsage() == null) {
            return null;
        }
        return cpu.getCpuUsage().getTotalUsage();
    }

    private static long onlineCpus(CpuStatsConfig cpu) {
        if (cpu == null) {
            return 0L;
        }
        Long online = cpu.getOnlineCpus();
        if (online != null && online > 0) {
            return online;
        }
        CpuUsageConfig usage = cpu.getCpuUsage();
        if (usage != null && usage.getPercpuUsage() != null && !usage.getPercpuUsage().isEmpty()) {
            return usage.getPercpuUsage().size();
        }
        return 0L;
    }

    private static long cacheBytes(StatsConfig stats) {
        if (stats == null) {
            return 0L;
        }
        Long inactiveFile = stats.getInactiveFile();
        Long cache = stats.getCache();
        Long chosen = inactiveFile != null ? inactiveFile : cache;
        if (chosen == null) {
            return 0L;
        }
        return Math.max(chosen, 0L);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ex) {
            log.debug("关闭容器统计失败: {}", ex.getMessage());
        }
    }

    /**
     * 一次采样。cpuCores 是使用中的 CPU 核数（不是百分比），memoryBytes 是扣掉缓存后的字节数。
     */
    public record Sample(double cpuCores, long memoryBytes) {

        public static final Sample ZERO = new Sample(0.0, 0L);
    }

    private static final class StatsCallback extends ResultCallback.Adapter<Statistics> {

        private Statistics first;

        @Override
        public void onNext(Statistics statistics) {
            if (first == null && statistics != null) {
                first = statistics;
            }
        }

        private Statistics first() {
            return first;
        }
    }
}
