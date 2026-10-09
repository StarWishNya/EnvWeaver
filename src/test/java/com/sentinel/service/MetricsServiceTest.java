package com.sentinel.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.sentinel.dto.ComponentHealth;
import com.sentinel.dto.SystemMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MetricsService 单元测试。
 */
@DisplayName("MetricsService 单元测试")
class MetricsServiceTest {

    private final List<MetricsService> services = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (MetricsService service : services) {
            service.shutdownHealthChecks();
        }
    }

    @Test
    @DisplayName("健康检查超时常量是 2 秒")
    void healthCheckTimeoutIsTwoSeconds() {
        assertThat(MetricsService.HEALTH_CHECK_TIMEOUT).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("按探针和容器列表组装系统指标")
    void collectsSchemaFields() {
        List<Container> containers = new ArrayList<>();
        containers.add(container("running"));
        containers.add(container("restarting"));
        containers.add(container("paused"));
        containers.add(container("exited"));
        containers.add(container("dead"));
        containers.add(container(null, "Up 3 seconds"));
        containers.add(container(null, "Up 3 seconds (Paused)"));
        containers.add(container("created"));
        containers.add(null);
        DockerClient dockerClient = dockerClientReturning(containers);
        MetricsService service = service(fixedHost(), dockerClient, List.of(), Duration.ofSeconds(2));

        LocalDateTime before = LocalDateTime.now().minusSeconds(2);
        SystemMetrics metrics = service.getSystemMetrics();

        assertThat(metrics.getTimestamp()).isAfter(before);
        assertThat(metrics.getCpu().getCores()).isEqualTo(4);
        assertThat(metrics.getCpu().getUsage()).isEqualTo(25d);
        assertThat(metrics.getMemory().getTotal()).isEqualTo(1000);
        assertThat(metrics.getMemory().getUsed()).isEqualTo(600);
        assertThat(metrics.getMemory().getUsage()).isEqualTo(60d);
        assertThat(metrics.getDisk().getTotal()).isEqualTo(2000);
        assertThat(metrics.getDisk().getUsed()).isEqualTo(1500);
        assertThat(metrics.getDisk().getUsage()).isEqualTo(75d);
        assertThat(metrics.getContainers().getRunning()).isEqualTo(3);
        assertThat(metrics.getContainers().getPaused()).isEqualTo(2);
        assertThat(metrics.getContainers().getStopped()).isEqualTo(4);
    }

    @Test
    @DisplayName("探针和 Docker 不可用时使用哨兵值")
    void usesFallbackWhenHostOrDockerUnavailable() {
        HostMetrics broken = new HostMetrics() {
            @Override
            public int availableProcessors() {
                throw new IllegalStateException("os.internal");
            }

            @Override
            public double systemCpuLoad() {
                throw new IllegalStateException("cpu.internal");
            }

            @Override
            public long totalPhysicalMemory() {
                throw new IllegalStateException("mem.internal");
            }

            @Override
            public long freePhysicalMemory() {
                return -1;
            }

            @Override
            public DiskSpace disk() {
                throw new IllegalStateException("disk.internal");
            }
        };
        DockerClient dockerClient = mock(DockerClient.class);
        when(dockerClient.listContainersCmd()).thenThrow(new IllegalStateException("tcp://docker.internal:2375"));
        MetricsService service = service(broken, dockerClient, List.of(), Duration.ofSeconds(2));

        SystemMetrics metrics = service.getSystemMetrics();

        assertThat(metrics.getCpu().getUsage()).isEqualTo(-1d);
        assertThat(metrics.getCpu().getCores()).isZero();
        assertThat(metrics.getMemory().getUsed()).isZero();
        assertThat(metrics.getMemory().getTotal()).isZero();
        assertThat(metrics.getMemory().getUsage()).isZero();
        assertThat(metrics.getDisk().getUsed()).isZero();
        assertThat(metrics.getDisk().getTotal()).isZero();
        assertThat(metrics.getContainers().getRunning()).isZero();
        assertThat(metrics.getContainers().getPaused()).isZero();
        assertThat(metrics.getContainers().getStopped()).isZero();
        assertThat(metrics.toString()).doesNotContain("internal");
    }

    @Test
    @DisplayName("容器列表超时或为空时数量为 0")
    void containerListingTimeoutReturnsZeros() {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd command = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(command);
        when(command.withShowAll(true)).thenReturn(command);
        when(command.exec()).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return List.of(container("running"));
        });
        MetricsService service = service(fixedHost(), dockerClient, List.of(), Duration.ofMillis(200));

        long started = System.nanoTime();
        SystemMetrics metrics = service.getSystemMetrics();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(metrics.getContainers().getRunning()).isZero();
        assertThat(countContainers(null).getRunning()).isZero();
        assertThat(countContainers(List.of()).getStopped()).isZero();
    }

    @Test
    @DisplayName("CPU、内存、磁盘换算覆盖不可用和越界")
    void convertsResourceNumbers() {
        assertThat(MetricsService.cpuUsage(Double.NaN)).isEqualTo(-1d);
        assertThat(MetricsService.cpuUsage(Double.POSITIVE_INFINITY)).isEqualTo(-1d);
        assertThat(MetricsService.cpuUsage(-0.1)).isEqualTo(-1d);
        assertThat(MetricsService.cpuUsage(0d)).isZero();
        assertThat(MetricsService.cpuUsage(0.5)).isEqualTo(50d);
        assertThat(MetricsService.cpuUsage(2d)).isEqualTo(100d);
        assertThat(MetricsService.cpuMetrics(-3, -1).getCores()).isZero();

        SystemMetrics.Memory empty = MetricsService.memoryMetrics(0, 10);
        assertThat(empty.getTotal()).isZero();
        assertThat(MetricsService.memoryMetrics(100, -1).getUsage()).isZero();
        assertThat(MetricsService.memoryMetrics(100, 150).getUsed()).isZero();

        SystemMetrics.Disk noDisk = MetricsService.diskMetrics(-1, 5);
        assertThat(noDisk.getTotal()).isZero();
        assertThat(MetricsService.diskMetrics(100, 250).getUsed()).isZero();
    }

    @Test
    @DisplayName("启动预热丢掉首次 CPU 0，无效读数回退到上一次非零样本")
    void cpuPrimeKeepsLastNonZeroSample() {
        AtomicInteger calls = new AtomicInteger();
        HostMetrics host = scriptedCpu(calls, 0d, 0.5d, Double.NaN, -1d);
        MetricsService service = service(host, mock(DockerClient.class), List.of(), Duration.ofSeconds(2));

        assertThat(service.getSystemMetrics().getCpu().getUsage()).isEqualTo(50d);
        assertThat(service.getSystemMetrics().getCpu().getCores()).isEqualTo(8);
        assertThat(service.getSystemMetrics().getCpu().getUsage()).isEqualTo(50d);
    }

    @Test
    @DisplayName("已有基线之后的 0 表示空闲")
    void laterZeroCpuIsIdle() {
        AtomicInteger calls = new AtomicInteger();
        HostMetrics host = scriptedCpu(calls, 0.5d, 0d);
        MetricsService service = service(host, mock(DockerClient.class), List.of(), Duration.ofSeconds(2));

        assertThat(service.getSystemMetrics().getCpu().getUsage()).isZero();
    }

    @Test
    @DisplayName("没有有效 CPU 样本时保持 -1")
    void cpuStaysUnavailableWithoutSample() {
        AtomicInteger calls = new AtomicInteger();
        HostMetrics host = scriptedCpu(calls, -1d, Double.NaN);
        MetricsService service = service(host, mock(DockerClient.class), List.of(), Duration.ofSeconds(2));

        assertThat(service.getSystemMetrics().getCpu().getUsage()).isEqualTo(-1d);
    }

    @Test
    @DisplayName("容器状态按 running、paused、stopped 归类")
    void classifiesContainerStates() {
        assertThat(MetricsService.classify("RUNNING", null)).isEqualTo("running");
        assertThat(MetricsService.classify("restarting", null)).isEqualTo("running");
        assertThat(MetricsService.classify("paused", "exited")).isEqualTo("paused");
        assertThat(MetricsService.classify("exited", null)).isEqualTo("stopped");
        assertThat(MetricsService.classify(" ", "Up 1 second")).isEqualTo("running");
        assertThat(MetricsService.classify(null, "Paused")).isEqualTo("paused");
        assertThat(MetricsService.classify(null, "Exited (0)")).isEqualTo("stopped");
        assertThat(MetricsService.classify(null, null)).isEqualTo("stopped");
    }

    @Test
    @DisplayName("三项都 UP 时健康状态为 UP")
    void healthIsUpWhenEveryComponentIsUp() {
        MetricsService service = service(fixedHost(), mock(DockerClient.class), List.of(
                checker(ComponentHealthChecker.DB, true),
                checker(ComponentHealthChecker.REDIS, true),
                checker(ComponentHealthChecker.DOCKER, true)
        ), Duration.ofSeconds(2));

        ComponentHealth health = service.checkHealth();

        assertThat(health.getStatus()).isEqualTo("UP");
        assertThat(health.getComponents().getDb()).isEqualTo("UP");
        assertThat(health.getComponents().getRedis()).isEqualTo("UP");
        assertThat(health.getComponents().getDocker()).isEqualTo("UP");
    }

    @Test
    @DisplayName("任一组件 DOWN、抛错或缺失时整体为 DOWN，且不带异常文本")
    void healthIsDownWithoutLeakingFailureDetails() {
        ComponentHealthChecker failingDb = new ComponentHealthChecker() {
            @Override
            public String name() {
                return DB;
            }

            @Override
            public boolean up() {
                throw new IllegalStateException("jdbc:mysql://db.internal:3306/sentinel");
            }
        };
        List<ComponentHealthChecker> checkers = new ArrayList<>();
        checkers.add(failingDb);
        checkers.add(checker(ComponentHealthChecker.REDIS, false));
        checkers.add(null);
        MetricsService service = service(fixedHost(), mock(DockerClient.class), checkers, Duration.ofSeconds(2));

        ComponentHealth health = service.checkHealth();

        assertThat(health.getStatus()).isEqualTo("DOWN");
        assertThat(health.getComponents().getDb()).isEqualTo("DOWN");
        assertThat(health.getComponents().getRedis()).isEqualTo("DOWN");
        assertThat(health.getComponents().getDocker()).isEqualTo("DOWN");
        assertThat(health.toString()).doesNotContain("db.internal").doesNotContain("Exception");
    }

    @Test
    @DisplayName("挂起的检查在超时后记为 DOWN")
    void hungCheckerTimesOutAsDown() {
        AtomicBoolean interrupted = new AtomicBoolean(false);
        ComponentHealthChecker slowDocker = new ComponentHealthChecker() {
            @Override
            public String name() {
                return DOCKER;
            }

            @Override
            public boolean up() {
                try {
                    Thread.sleep(5_000);
                    return true;
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        };
        MetricsService service = service(fixedHost(), mock(DockerClient.class), List.of(
                checker(ComponentHealthChecker.DB, true),
                checker(ComponentHealthChecker.REDIS, true),
                slowDocker
        ), Duration.ofMillis(200));

        long started = System.nanoTime();
        ComponentHealth health = service.checkHealth();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(health.getStatus()).isEqualTo("DOWN");
        assertThat(health.getComponents().getDb()).isEqualTo("UP");
        assertThat(health.getComponents().getRedis()).isEqualTo("UP");
        assertThat(health.getComponents().getDocker()).isEqualTo("DOWN");
        assertThat(health.toString()).doesNotContain("Exception");
    }

    @Test
    @DisplayName("重复检查里只要有一次 DOWN，组件就是 DOWN")
    void oneDownCheckKeepsComponentDown() {
        MetricsService service = service(fixedHost(), mock(DockerClient.class), List.of(
                checker(ComponentHealthChecker.DB, true),
                checker(ComponentHealthChecker.DB, false),
                checker(ComponentHealthChecker.REDIS, true),
                checker(ComponentHealthChecker.DOCKER, true),
                checker("queue", false)
        ), Duration.ofSeconds(2));

        ComponentHealth health = service.checkHealth();

        assertThat(health.getComponents().getDb()).isEqualTo("DOWN");
        assertThat(health.getStatus()).isEqualTo("DOWN");
        assertThat(health.getComponents().getRedis()).isEqualTo("UP");
        assertThat(health.getComponents().getDocker()).isEqualTo("UP");
    }

    @Test
    @DisplayName("没有检查器时三项都是 DOWN")
    void missingCheckersAreDown() {
        MetricsService service = service(fixedHost(), null, null, null);

        ComponentHealth health = service.checkHealth();

        assertThat(health.getStatus()).isEqualTo("DOWN");
        assertThat(health.getComponents().getDb()).isEqualTo("DOWN");
        assertThat(health.getComponents().getRedis()).isEqualTo("DOWN");
        assertThat(health.getComponents().getDocker()).isEqualTo("DOWN");
    }

    @Test
    @DisplayName("本机探针能读到工作目录磁盘，读不到的路径不抛错")
    void systemProbeReadsThisHostAndHidesDiskErrors() {
        HostMetrics host = HostMetrics.system();
        assertThat(host.availableProcessors()).isGreaterThan(0);
        assertThat(host.systemCpuLoad()).isGreaterThanOrEqualTo(-1d);
        assertThat(host.totalPhysicalMemory()).isNotZero();
        assertThat(host.freePhysicalMemory()).isGreaterThanOrEqualTo(-1L);
        assertThat(host.disk().totalBytes()).isGreaterThan(0);

        HostMetrics.DiskSpace missing = HostMetrics.SystemHostMetrics.readDisk(
                java.nio.file.Path.of("/no/such/sentinel-metrics-" + System.nanoTime()));
        assertThat(missing.totalBytes()).isEqualTo(-1L);
    }

    private MetricsService service(HostMetrics hostMetrics,
                                   DockerClient dockerClient,
                                   List<ComponentHealthChecker> checkers,
                                   Duration timeout) {
        MetricsService service = new MetricsService(hostMetrics, dockerClient, checkers, timeout);
        services.add(service);
        return service;
    }

    private static HostMetrics fixedHost() {
        return new HostMetrics() {
            @Override
            public int availableProcessors() {
                return 4;
            }

            @Override
            public double systemCpuLoad() {
                return 0.25d;
            }

            @Override
            public long totalPhysicalMemory() {
                return 1000;
            }

            @Override
            public long freePhysicalMemory() {
                return 400;
            }

            @Override
            public DiskSpace disk() {
                return new DiskSpace(2000, 500);
            }
        };
    }

    private static HostMetrics scriptedCpu(AtomicInteger calls, double... samples) {
        return new HostMetrics() {
            @Override
            public int availableProcessors() {
                return 8;
            }

            @Override
            public double systemCpuLoad() {
                int index = calls.getAndIncrement();
                if (index >= samples.length) {
                    return samples[samples.length - 1];
                }
                return samples[index];
            }

            @Override
            public long totalPhysicalMemory() {
                return -1L;
            }

            @Override
            public long freePhysicalMemory() {
                return -1L;
            }

            @Override
            public DiskSpace disk() {
                return new DiskSpace(-1L, -1L);
            }
        };
    }

    private static DockerClient dockerClientReturning(List<Container> containers) {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd command = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(command);
        when(command.withShowAll(true)).thenReturn(command);
        when(command.exec()).thenReturn(containers);
        return dockerClient;
    }

    private static Container container(String state) {
        return container(state, null);
    }

    private static Container container(String state, String status) {
        Container container = new Container();
        setField(container, "state", state);
        setField(container, "status", status);
        return container;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SystemMetrics.Containers countContainers(List<Container> containers) {
        return MetricsService.countContainers(containers);
    }

    private static ComponentHealthChecker checker(String name, boolean up) {
        return new ComponentHealthChecker() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean up() {
                return up;
            }
        };
    }
}
