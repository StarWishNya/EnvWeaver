package com.sentinel.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.sentinel.dto.ComponentHealth;
import com.sentinel.dto.SystemMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 采集系统指标，并在短超时内汇总数据库、Redis、Docker 健康状态。
 *
 * @author Sentinel Team
 */
@Slf4j
@Service
public class MetricsService {

    /**
     * 单项健康检查的最长时间。超时记为 DOWN，不把失败原因写进响应。
     */
    public static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(2);

    private final HostMetrics hostMetrics;
    private final DockerClient dockerClient;
    private final List<ComponentHealthChecker> checkers;
    private final Duration timeout;
    private final ProvisioningCapacity provisioningCapacity;
    private final ExecutorService healthExecutor;
    private final HostMetrics.CpuLoadSamples cpuLoadSamples = new HostMetrics.CpuLoadSamples();
    private final AtomicBoolean cpuPrimeStarted = new AtomicBoolean(false);

    @Autowired
    public MetricsService(DockerClient dockerClient,
                          List<ComponentHealthChecker> checkers,
                          ObjectProvider<ProvisioningCapacity> provisioningCapacity) {
        this(HostMetrics.system(), dockerClient, checkers, HEALTH_CHECK_TIMEOUT,
                provisioningCapacity == null ? null : provisioningCapacity.getIfAvailable());
    }

    MetricsService(HostMetrics hostMetrics,
                   DockerClient dockerClient,
                   List<ComponentHealthChecker> checkers,
                   Duration timeout) {
        this(hostMetrics, dockerClient, checkers, timeout, null);
    }

    MetricsService(HostMetrics hostMetrics,
                   DockerClient dockerClient,
                   List<ComponentHealthChecker> checkers,
                   Duration timeout,
                   ProvisioningCapacity provisioningCapacity) {
        this.hostMetrics = hostMetrics;
        this.dockerClient = dockerClient;
        this.checkers = checkers == null
                ? List.of()
                : checkers.stream().filter(Objects::nonNull).toList();
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? HEALTH_CHECK_TIMEOUT
                : timeout;
        this.provisioningCapacity = provisioningCapacity;
        this.healthExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "metrics-health-check");
            thread.setDaemon(true);
            return thread;
        });
        primeCpuLoad();
    }

    /**
     * 启动时先读 CPU，避免第一次 /api/metrics 拿到尚未建立采样间隔时的 0。
     */
    @PostConstruct
    void primeCpuLoad() {
        if (!cpuPrimeStarted.compareAndSet(false, true)) {
            return;
        }
        cpuLoadSamples.prime(this::readHostCpuQuietly);
    }

    /**
     * 当前 CPU、内存、磁盘和容器数量。采集失败的项用哨兵值，不抛给控制器。
     */
    public SystemMetrics getSystemMetrics() {
        SystemMetrics metrics = new SystemMetrics();
        metrics.setTimestamp(LocalDateTime.now());
        metrics.setCpu(readCpu());
        metrics.setMemory(readMemory());
        metrics.setDisk(readDisk());
        metrics.setContainers(readContainers());
        applyProvisioning(metrics);
        return metrics;
    }

    private void applyProvisioning(SystemMetrics metrics) {
        ProvisioningUsage usage = readProvisioningUsage();
        metrics.setActiveEnvironments(usage.activeEnvironments());
        metrics.setActiveContainers(usage.activeContainers());
        metrics.setQueuedTasks(usage.queuedTasks());
        SystemMetrics.Limits limits = new SystemMetrics.Limits();
        limits.setMaxActiveEnvironments(usage.maxActiveEnvironments());
        limits.setMaxContainers(usage.maxContainers());
        metrics.setLimits(limits);
    }

    private ProvisioningUsage readProvisioningUsage() {
        if (provisioningCapacity == null) {
            return ProvisioningUsage.defaults();
        }
        try {
            ProvisioningUsage usage = provisioningCapacity.usage();
            return usage == null ? ProvisioningUsage.defaults() : usage;
        } catch (RuntimeException ex) {
            log.warn("供给占用读取失败: {}", ex.getClass().getSimpleName());
            return ProvisioningUsage.defaults();
        }
    }

    /**
     * 三项都为 UP 时整体为 UP，否则为 DOWN。检查本身不会抛出异常。
     */
    public ComponentHealth checkHealth() {
        List<Future<CheckResult>> futures = new ArrayList<>();
        for (ComponentHealthChecker checker : checkers) {
            try {
                futures.add(healthExecutor.submit(() -> probe(checker)));
            } catch (Exception e) {
                log.warn("Health check was not started: {}", e.getClass().getSimpleName());
            }
        }

        String db = null;
        String redis = null;
        String docker = null;
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Future<CheckResult> future : futures) {
            CheckResult result = await(future, deadline);
            if (result == null || result.name == null) {
                continue;
            }
            db = merge(db, result, ComponentHealthChecker.DB);
            redis = merge(redis, result, ComponentHealthChecker.REDIS);
            docker = merge(docker, result, ComponentHealthChecker.DOCKER);
        }

        ComponentHealth.Components components = new ComponentHealth.Components();
        components.setDb(orDown(db));
        components.setRedis(orDown(redis));
        components.setDocker(orDown(docker));

        ComponentHealth health = new ComponentHealth();
        boolean up = "UP".equals(components.getDb())
                && "UP".equals(components.getRedis())
                && "UP".equals(components.getDocker());
        health.setStatus(up ? "UP" : "DOWN");
        health.setComponents(components);
        return health;
    }

    @PreDestroy
    public void shutdownHealthChecks() {
        healthExecutor.shutdownNow();
    }

    private SystemMetrics.Cpu readCpu() {
        int cores = 0;
        try {
            cores = hostMetrics.availableProcessors();
        } catch (Exception e) {
            log.warn("CPU core count unavailable: {}", e.getClass().getSimpleName());
        }
        double load = cpuLoadSamples.observe(readHostCpuQuietly(), cpuLoadSamples.hasSample());
        return cpuMetrics(cores, load);
    }

    private double readHostCpuQuietly() {
        try {
            return hostMetrics.systemCpuLoad();
        } catch (Exception e) {
            log.warn("CPU load unavailable: {}", e.getClass().getSimpleName());
            return -1d;
        }
    }

    private SystemMetrics.Memory readMemory() {
        long total = -1L;
        long free = -1L;
        try {
            total = hostMetrics.totalPhysicalMemory();
            free = hostMetrics.freePhysicalMemory();
        } catch (Exception e) {
            log.warn("Memory metrics unavailable: {}", e.getClass().getSimpleName());
        }
        return memoryMetrics(total, free);
    }

    private SystemMetrics.Disk readDisk() {
        try {
            HostMetrics.DiskSpace space = hostMetrics.disk();
            if (space == null) {
                return diskMetrics(-1L, -1L);
            }
            return diskMetrics(space.totalBytes(), space.unallocatedBytes());
        } catch (Exception e) {
            log.warn("Disk metrics unavailable: {}", e.getClass().getSimpleName());
            return diskMetrics(-1L, -1L);
        }
    }

    private SystemMetrics.Containers readContainers() {
        Future<SystemMetrics.Containers> future;
        try {
            future = healthExecutor.submit(this::listContainersQuietly);
        } catch (Exception e) {
            log.warn("Container metrics were not started: {}", e.getClass().getSimpleName());
            return SystemMetrics.Containers.zeros();
        }
        try {
            SystemMetrics.Containers counts = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return counts == null ? SystemMetrics.Containers.zeros() : counts;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return SystemMetrics.Containers.zeros();
        } catch (Exception e) {
            future.cancel(true);
            log.warn("Container metrics unavailable: {}", e.getClass().getSimpleName());
            return SystemMetrics.Containers.zeros();
        }
    }

    private SystemMetrics.Containers listContainersQuietly() {
        try {
            if (dockerClient == null) {
                return SystemMetrics.Containers.zeros();
            }
            List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
            return countContainers(containers);
        } catch (Exception e) {
            log.warn("Container metrics unavailable: {}", e.getClass().getSimpleName());
            return SystemMetrics.Containers.zeros();
        }
    }

    static SystemMetrics.Cpu cpuMetrics(int cores, double load) {
        SystemMetrics.Cpu cpu = new SystemMetrics.Cpu();
        cpu.setCores(Math.max(cores, 0));
        cpu.setUsage(cpuUsage(load));
        return cpu;
    }

    static double cpuUsage(double load) {
        if (Double.isNaN(load) || Double.isInfinite(load) || load < 0d) {
            return -1d;
        }
        return Math.min(100d, load * 100d);
    }

    static SystemMetrics.Memory memoryMetrics(long total, long free) {
        SystemMetrics.Memory memory = new SystemMetrics.Memory();
        if (total <= 0 || free < 0) {
            memory.setUsed(0);
            memory.setTotal(0);
            memory.setUsage(0);
            return memory;
        }
        long used = total - Math.min(free, total);
        memory.setTotal(total);
        memory.setUsed(used);
        memory.setUsage(used * 100d / total);
        return memory;
    }

    static SystemMetrics.Disk diskMetrics(long total, long unallocated) {
        SystemMetrics.Disk disk = new SystemMetrics.Disk();
        if (total <= 0 || unallocated < 0) {
            disk.setUsed(0);
            disk.setTotal(0);
            disk.setUsage(0);
            return disk;
        }
        long used = total - Math.min(unallocated, total);
        disk.setTotal(total);
        disk.setUsed(used);
        disk.setUsage(used * 100d / total);
        return disk;
    }

    static SystemMetrics.Containers countContainers(List<Container> containers) {
        SystemMetrics.Containers counts = SystemMetrics.Containers.zeros();
        if (containers == null) {
            return counts;
        }
        int running = 0;
        int paused = 0;
        int stopped = 0;
        for (Container container : containers) {
            String kind = container == null
                    ? "stopped"
                    : classify(container.getState(), container.getStatus());
            switch (kind) {
                case "running" -> running++;
                case "paused" -> paused++;
                default -> stopped++;
            }
        }
        counts.setRunning(running);
        counts.setPaused(paused);
        counts.setStopped(stopped);
        return counts;
    }

    static String classify(String state, String status) {
        String normalized = normalize(state);
        if (!normalized.isEmpty()) {
            return switch (normalized) {
                case "running", "restarting", "up" -> "running";
                case "paused" -> "paused";
                default -> "stopped";
            };
        }
        String fromStatus = normalize(status);
        if (fromStatus.startsWith("up")) {
            return fromStatus.contains("paused") ? "paused" : "running";
        }
        if (fromStatus.startsWith("paused")) {
            return "paused";
        }
        return "stopped";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private CheckResult probe(ComponentHealthChecker checker) {
        String name = "unknown";
        try {
            String reported = checker.name();
            if (reported != null) {
                name = reported;
            }
            return new CheckResult(name, checker.up() ? "UP" : "DOWN");
        } catch (Exception e) {
            log.warn("Health check failed for {}: {}", name, e.getClass().getSimpleName());
            return new CheckResult(name, "DOWN");
        }
    }

    private CheckResult await(Future<CheckResult> future, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            future.cancel(true);
            return null;
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        } catch (Exception e) {
            future.cancel(true);
            return null;
        }
    }

    private static String merge(String current, CheckResult result, String expected) {
        if (!expected.equals(result.name)) {
            return current;
        }
        boolean up = "UP".equals(result.status);
        if (current == null) {
            return up ? "UP" : "DOWN";
        }
        if (!up || "DOWN".equals(current)) {
            return "DOWN";
        }
        return "UP";
    }

    private static String orDown(String status) {
        return "UP".equals(status) ? "UP" : "DOWN";
    }

    private static final class CheckResult {
        private final String name;
        private final String status;

        private CheckResult(String name, String status) {
            this.name = name;
            this.status = status;
        }
    }
}
