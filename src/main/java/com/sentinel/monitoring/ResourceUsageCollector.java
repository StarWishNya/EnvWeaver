package com.sentinel.monitoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.domain.Container;
import com.sentinel.domain.Environment;
import com.sentinel.mapper.ContainerMapper;
import com.sentinel.mapper.EnvironmentMapper;
import com.sentinel.monitoring.ContainerStatsReader.Sample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把运行中容器的 CPU、内存写入 t_environment.resource_usage。
 * 只更新 resource_usage，不改 status。空闲环境如果还留着采样，写成 NULL。
 *
 * @author Sentinel Team
 */
@Slf4j
@Component
public class ResourceUsageCollector implements DisposableBean {

    /**
     * 会采样的环境状态。RETAINED 是失败后保留现场的环境，仍然占用容器。
     */
    static final List<String> SAMPLED_STATUSES = List.of("READY", "BUSY", "RETAINED");

    private static final DateTimeFormatter SAMPLED_AT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final long BYTES_PER_MB = 1024L * 1024L;

    /**
     * 关闭采集时不建线程池，读取在调用线程上顺序完成。
     */
    static final Executor SAME_THREAD = Runnable::run;

    private final ResourceUsageProperties properties;
    private final EnvironmentMapper environmentMapper;
    private final ContainerMapper containerMapper;
    private final ContainerStatsReader statsReader;
    private final Executor containerReads;
    private final Clock clock;
    private final boolean shutdownPool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ResourceUsageCollector(ResourceUsageProperties properties,
                                  EnvironmentMapper environmentMapper,
                                  ContainerMapper containerMapper,
                                  ContainerStatsReader statsReader) {
        this(properties, environmentMapper, containerMapper, statsReader,
                properties != null && properties.isEnabled() ? newPool(properties.getParallelism()) : SAME_THREAD,
                Clock.systemDefaultZone(),
                properties != null && properties.isEnabled());
    }

    ResourceUsageCollector(ResourceUsageProperties properties,
                           EnvironmentMapper environmentMapper,
                           ContainerMapper containerMapper,
                           ContainerStatsReader statsReader,
                           Executor containerReads,
                           Clock clock,
                           boolean shutdownPool) {
        this.properties = properties;
        this.environmentMapper = environmentMapper;
        this.containerMapper = containerMapper;
        this.statsReader = statsReader;
        this.containerReads = containerReads;
        this.clock = clock;
        this.shutdownPool = shutdownPool;
    }

    /**
     * 间隔来自 sentinel.monitoring.resource-usage.interval。
     * fixedDelayString 用毫秒数，因为 Spring 6.0 不能解析 30s。
     */
    @Scheduled(
            fixedDelayString = "#{@resourceUsageProperties.interval.toMillis()}",
            initialDelayString = "#{@resourceUsageProperties.interval.toMillis()}")
    public void collect() {
        if (properties == null || !properties.isEnabled()) {
            log.debug("资源采集未启用，跳过");
            return;
        }
        try {
            sampleActive();
            clearIdle();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("资源采集被中断");
        }
    }

    @Override
    public void destroy() {
        if (shutdownPool && containerReads instanceof ExecutorService executorService) {
            executorService.shutdownNow();
        }
    }

    private void sampleActive() throws InterruptedException {
        List<Environment> environments = environmentMapper.selectByStatuses(SAMPLED_STATUSES, limit());
        if (environments == null || environments.isEmpty()) {
            return;
        }
        int updated = 0;
        for (Environment environment : environments) {
            if (environment == null || environment.getId() == null) {
                continue;
            }
            try {
                if (sampleOne(environment)) {
                    updated++;
                }
            } catch (RuntimeException ex) {
                log.warn("采集环境资源失败，继续处理其余环境: envId={}, reason={}",
                        environment.getId(), ex.getMessage());
            }
        }
        if (updated > 0) {
            log.info("已更新环境资源使用: count={}", updated);
        }
    }

    /**
     * @return 是否写了新的 resource_usage
     */
    private boolean sampleOne(Environment environment) throws InterruptedException {
        List<Container> containers = containerMapper.findRunningByEnvironmentId(environment.getId());
        if (containers == null) {
            log.warn("查询运行中容器失败，保留原资源使用: envId={}", environment.getId());
            return false;
        }
        Optional<Sample> combined = combine(environment.getId(), containers);
        if (combined.isEmpty()) {
            log.warn("环境容器统计全部失败，保留原资源使用: envId={}", environment.getId());
            return false;
        }
        environmentMapper.updateResourceUsage(environment.getId(), toJson(combined.get()));
        return true;
    }

    /**
     * 没有运行中的容器时返回 0，用来清掉过期负载。
     * 有容器但每一条都读失败时返回 empty，调用方保留原值。
     */
    private Optional<Sample> combine(Long environmentId, List<Container> containers) throws InterruptedException {
        List<String> containerIds = new ArrayList<>();
        for (Container container : containers) {
            if (container == null) {
                continue;
            }
            String containerId = container.getContainerId();
            containerIds.add(containerId == null ? "" : containerId.trim());
        }
        if (containerIds.isEmpty()) {
            return Optional.of(Sample.ZERO);
        }

        List<CompletableFuture<Optional<Sample>>> futures = new ArrayList<>(containerIds.size());
        for (String containerId : containerIds) {
            if (containerId.isEmpty()) {
                futures.add(CompletableFuture.completedFuture(Optional.empty()));
                continue;
            }
            futures.add(CompletableFuture.supplyAsync(() -> readQuietly(environmentId, containerId), containerReads));
        }
        return awaitSum(environmentId, futures);
    }

    private Optional<Sample> awaitSum(Long environmentId,
                                      List<CompletableFuture<Optional<Sample>>> futures) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readBudgetMs(futures.size()));
        double cpu = 0.0;
        long memory = 0L;
        int success = 0;
        try {
            for (CompletableFuture<Optional<Sample>> future : futures) {
                long remain = deadline - System.nanoTime();
                if (remain <= 0) {
                    future.cancel(true);
                    continue;
                }
                try {
                    Optional<Sample> sample = future.get(remain, TimeUnit.NANOSECONDS);
                    if (sample != null && sample.isPresent()) {
                        success++;
                        cpu += sanitizeCpu(sample.get().cpuCores());
                        memory = addBytes(memory, sample.get().memoryBytes());
                    }
                } catch (TimeoutException ex) {
                    future.cancel(true);
                } catch (ExecutionException ex) {
                    log.debug("读取容器资源失败: envId={}, reason={}", environmentId, causeMessage(ex));
                } catch (CancellationException ex) {
                    log.debug("读取容器资源已取消: envId={}", environmentId);
                }
            }
        } catch (InterruptedException ex) {
            for (CompletableFuture<?> future : futures) {
                future.cancel(true);
            }
            throw ex;
        }
        if (success == 0) {
            return Optional.empty();
        }
        if (success < futures.size()) {
            log.warn("部分容器统计失败，已按成功的容器汇总: envId={}, succeeded={}, failed={}",
                    environmentId, success, futures.size() - success);
        }
        return Optional.of(new Sample(cpu, memory));
    }

    private Optional<Sample> readQuietly(Long environmentId, String containerId) {
        try {
            Optional<Sample> sample = statsReader.read(containerId);
            return sample == null ? Optional.empty() : sample;
        } catch (RuntimeException ex) {
            log.debug("读取容器资源失败: envId={}, containerId={}, reason={}",
                    environmentId, containerId, ex.getMessage());
            return Optional.empty();
        }
    }

    private void clearIdle() {
        List<Environment> idle = environmentMapper.selectIdleWithResourceUsage(limit());
        if (idle == null || idle.isEmpty()) {
            return;
        }
        int cleared = 0;
        for (Environment environment : idle) {
            if (environment == null || environment.getId() == null) {
                continue;
            }
            try {
                environmentMapper.updateResourceUsage(environment.getId(), null);
                cleared++;
            } catch (RuntimeException ex) {
                log.warn("清空空闲环境资源使用失败: envId={}, reason={}",
                        environment.getId(), ex.getMessage());
            }
        }
        if (cleared > 0) {
            log.info("已清空空闲环境的资源使用: count={}", cleared);
        }
    }

    /**
     * cpu 保留 3 位小数，memory 为 MiB 整数。与 DockerService 一样按 1024*1024 换算。
     * sampledAt 是 ISO 本地时间。数字保持 JSON 数字，不写成字符串。
     */
    private String toJson(Sample sample) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cpu", roundCpu(sample.cpuCores()));
        body.put("memory", memoryMb(sample.memoryBytes()));
        body.put("sampledAt", LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS).format(SAMPLED_AT));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("资源使用 JSON 序列化失败", ex);
        }
    }

    static double roundCpu(double cores) {
        if (!Double.isFinite(cores) || cores <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(cores).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    static Number memoryMb(long bytes) {
        if (bytes <= 0) {
            return 0;
        }
        long rounded = Math.round(bytes / (double) BYTES_PER_MB);
        if (rounded <= 0) {
            return 0;
        }
        if (rounded > Integer.MAX_VALUE) {
            return rounded;
        }
        return (int) rounded;
    }

    private int limit() {
        int configured = properties.getMaxEnvironmentsPerPass();
        return configured <= 0 ? 200 : configured;
    }

    private long readBudgetMs(int tasks) {
        long readMs = DEFAULT_READ_MS;
        Duration timeout = properties.getReadTimeout();
        if (timeout != null && !timeout.isNegative()) {
            readMs = timeout.toMillis();
        }
        int pool = Math.max(properties.getParallelism(), 1);
        long batches = (Math.max(tasks, 1) + (long) pool - 1L) / pool;
        return readMs * Math.max(batches, 1L) + 1_000L;
    }

    private static final long DEFAULT_READ_MS = ContainerStatsReader.DEFAULT_READ_TIMEOUT.toMillis();

    private static double sanitizeCpu(double cores) {
        if (!Double.isFinite(cores) || cores < 0) {
            return 0.0;
        }
        return cores;
    }

    private static long addBytes(long left, long right) {
        if (right <= 0) {
            return left;
        }
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static String causeMessage(ExecutionException ex) {
        Throwable cause = ex.getCause();
        return cause == null || cause.getMessage() == null ? ex.getMessage() : cause.getMessage();
    }

    private static ExecutorService newPool(int parallelism) {
        int size = parallelism <= 0 ? 4 : parallelism;
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(size, runnable -> {
            Thread thread = new Thread(runnable, "sentinel-resource-usage-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
