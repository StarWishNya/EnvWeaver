package com.sentinel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.DockerProperties;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.domain.Container;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Task;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.ContainerMapper;
import com.sentinel.mapper.EnvironmentMapper;
import com.sentinel.service.provision.ContainerLauncher;
import com.sentinel.service.provision.DependencyCatalog;
import com.sentinel.service.provision.PortAllocator;
import com.sentinel.service.provision.ProvisioningFailedException;
import com.sentinel.service.provision.ReadinessPause;
import com.sentinel.service.provision.TcpProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 环境供给：解析依赖、写入 READY、失败清理、重复调用。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("环境供给")
class DockerEnvironmentProvisionerTest {

    @Mock
    private EnvironmentMapper environmentMapper;

    @Mock
    private ContainerMapper containerMapper;

    @Mock
    private PortAllocator portAllocator;

    @Mock
    private ContainerLauncher containerLauncher;

    @Mock
    private TcpProbe tcpProbe;

    @Mock
    private ReadinessPause readinessPause;

    private ProvisioningProperties provisioningProperties;
    private DockerEnvironmentProvisioner provisioner;

    @BeforeEach
    void setUp() {
        provisioningProperties = new ProvisioningProperties();
        DockerProperties dockerProperties = new DockerProperties();
        provisioner = new DockerEnvironmentProvisioner(
                environmentMapper,
                containerMapper,
                new DependencyCatalog(provisioningProperties),
                portAllocator,
                containerLauncher,
                tcpProbe,
                readinessPause,
                provisioningProperties,
                dockerProperties);
    }

    @Test
    @DisplayName("成功时环境变为 READY，并写上端口映射")
    void successMarksEnvironmentReadyWithPortMappings() throws Exception {
        when(portAllocator.allocate(2)).thenReturn(List.of(30010, 30011));
        stubEnvironmentInsert();
        List<String> healthAtInsert = new java.util.ArrayList<>();
        AtomicInteger containerIds = new AtomicInteger(1);
        when(containerMapper.insert(any(Container.class))).thenAnswer(invocation -> {
            Container container = invocation.getArgument(0);
            healthAtInsert.add(container.getHealthStatus());
            container.setId((long) containerIds.getAndIncrement());
            return 1;
        });
        when(containerLauncher.start(any())).thenAnswer(invocation -> {
            ContainerLauncher.Launch launch = invocation.getArgument(0);
            boolean healthcheck = "mysql:8.0".equals(launch.image());
            return new ContainerLauncher.StartedContainer("cid-" + launch.name(), healthcheck);
        });
        AtomicInteger mysqlPolls = new AtomicInteger();
        when(containerLauncher.inspect(anyString())).thenAnswer(invocation -> {
            String containerId = invocation.getArgument(0);
            if (containerId.contains("-mysql") && mysqlPolls.getAndIncrement() == 0) {
                return new ContainerLauncher.Snapshot(true, "starting");
            }
            if (containerId.contains("-mysql")) {
                return new ContainerLauncher.Snapshot(true, "healthy");
            }
            return new ContainerLauncher.Snapshot(true, null);
        });
        when(tcpProbe.accepts(anyString(), anyInt())).thenReturn(true);

        Task task = task(7L, "[\"mysql:8.0\", \"redis:7.0\"]");
        Environment created = provisioner.provision(task);

        ArgumentCaptor<Environment> inserted = ArgumentCaptor.forClass(Environment.class);
        verify(environmentMapper).insert(inserted.capture());
        Environment stored = inserted.getValue();
        assertThat(created).isSameAs(stored);
        assertThat(stored.getStatus()).isEqualTo("READY");
        assertThat(stored.getEnvCode()).matches("env-[0-9a-f]{8}");
        assertThat(stored.getTaskId()).isEqualTo(7L);
        assertThat(stored.getAccessUrl()).isEqualTo("localhost");
        assertThat(stored.getResourceUsage()).isNull();
        assertThat(stored.getExpiresAt()).isAfter(LocalDateTime.now().plusHours(2).minusMinutes(2));
        assertThat(stored.getExpiresAt()).isBefore(LocalDateTime.now().plusHours(2).plusMinutes(2));

        JsonNode mappings = new ObjectMapper().readTree(stored.getPortMappings());
        assertThat(mappings.get("mysql").asInt()).isEqualTo(30010);
        assertThat(mappings.get("redis").asInt()).isEqualTo(30011);

        String compose = stored.getComposeContent();
        assertThat(compose).contains("mysql:8.0")
                .contains("redis:7-alpine")
                .contains("sentinel-env-" + stored.getEnvCode() + "-mysql")
                .contains("sentinel-env-" + stored.getEnvCode() + "-redis")
                .contains("30010:3306")
                .contains("30011:6379")
                .contains("MYSQL_DATABASE")
                .contains("mem_limit")
                .contains("sentinel-network");

        ArgumentCaptor<ContainerLauncher.Launch> launches = ArgumentCaptor.forClass(ContainerLauncher.Launch.class);
        verify(containerLauncher).ensureImage("mysql:8.0");
        verify(containerLauncher).ensureImage("redis:7-alpine");
        verify(containerLauncher, times(2)).start(launches.capture());
        ContainerLauncher.Launch mysql = launches.getAllValues().get(0);
        assertThat(mysql.name()).isEqualTo("sentinel-env-" + stored.getEnvCode() + "-mysql");
        assertThat(mysql.containerPort()).isEqualTo(3306);
        assertThat(mysql.hostPort()).isEqualTo(30010);
        assertThat(mysql.labels()).containsEntry("sentinel.env", stored.getEnvCode()).containsEntry("sentinel.task", "7");
        assertThat(mysql.env()).containsEntry("MYSQL_DATABASE", "test").containsKey("MYSQL_ROOT_PASSWORD");
        assertThat(launches.getAllValues().get(1).env()).isEmpty();
        assertThat(launches.getAllValues().get(1).hostPort()).isEqualTo(30011);

        ArgumentCaptor<Container> containers = ArgumentCaptor.forClass(Container.class);
        verify(containerMapper, times(2)).insert(containers.capture());
        assertThat(containers.getAllValues()).extracting(Container::getStatus).containsExactly("RUNNING", "RUNNING");
        assertThat(containers.getAllValues()).extracting(Container::getPortMapping).containsExactly("3306:30010", "6379:30011");
        assertThat(healthAtInsert).containsExactly("UNKNOWN", "UNKNOWN");
        assertThat(containers.getAllValues()).extracting(Container::getImage).containsExactly("mysql:8.0", "redis:7-alpine");

        ArgumentCaptor<Container> healthUpdates = ArgumentCaptor.forClass(Container.class);
        verify(containerMapper, times(2)).updateById(healthUpdates.capture());
        assertThat(healthUpdates.getAllValues()).extracting(Container::getHealthStatus).containsExactly("HEALTHY", "HEALTHY");

        verify(environmentMapper).updateById(stored);
        verify(environmentMapper, never()).markProvisionFailed(any());
        verify(containerLauncher, never()).remove(anyString());
        verify(portAllocator).release(List.of(30010, 30011));
        verify(readinessPause).pause(any());
    }

    @Test
    @DisplayName("没有依赖时环境直接 READY，不启动容器")
    void emptyDependenciesCreateReadyEnvironmentWithoutContainers() {
        AtomicInteger attempts = new AtomicInteger();
        when(environmentMapper.insert(any(Environment.class))).thenAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                throw new DuplicateKeyException("env_code");
            }
            Environment environment = invocation.getArgument(0);
            environment.setId(50L);
            return 1;
        });

        Environment created = provisioner.provision(task(3L, null));

        assertThat(created.getStatus()).isEqualTo("READY");
        assertThat(created.getPortMappings()).isEqualTo("{}");
        assertThat(created.getComposeContent()).contains("services:");
        assertThat(created.getResourceUsage()).isNull();
        verify(environmentMapper, times(2)).insert(any(Environment.class));
        verifyNoInteractions(containerLauncher);
        verifyNoInteractions(portAllocator);
        verifyNoInteractions(containerMapper);
    }

    @Test
    @DisplayName("第二个容器启动失败时删除已启动容器，并把环境标为 ERROR")
    void failureRemovesStartedContainersAndMarksEnvironmentError() {
        when(portAllocator.allocate(2)).thenReturn(List.of(30010, 30011));
        stubEnvironmentInsert();
        stubContainerInsert();
        AtomicInteger starts = new AtomicInteger();
        when(containerLauncher.start(any())).thenAnswer(invocation -> {
            if (starts.incrementAndGet() == 2) {
                throw new BusinessException(ErrorCode.DOCKER_ERROR, "启动容器失败");
            }
            ContainerLauncher.Launch launch = invocation.getArgument(0);
            return new ContainerLauncher.StartedContainer("cid-" + launch.name(), false);
        });

        BusinessException failure = catchThrowableOfType(
                () -> provisioner.provision(task(7L, "mysql,redis")), BusinessException.class);

        assertThat(failure).isInstanceOf(ProvisioningFailedException.class);
        assertThat(((ProvisioningFailedException) failure).getEnvironmentId()).isEqualTo(42L);
        assertThat(failure.getCode()).isEqualTo(ErrorCode.DOCKER_ERROR.getCode());
        assertThat(failure.getMessage()).isEqualTo("启动 redis 失败");
        assertThat(failure.getMessage()).doesNotContain("\n").doesNotContain("\tat");

        ArgumentCaptor<Environment> inserted = ArgumentCaptor.forClass(Environment.class);
        verify(environmentMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo("ERROR");
        assertThat(inserted.getValue().getPortMappings()).isNull();
        verify(environmentMapper).markProvisionFailed(42L);
        verify(environmentMapper, never()).updateById(any(Environment.class));

        ArgumentCaptor<Container> errored = ArgumentCaptor.forClass(Container.class);
        verify(containerMapper).updateById(errored.capture());
        assertThat(errored.getValue().getStatus()).isEqualTo("ERROR");
        assertThat(errored.getValue().getContainerName()).contains("-mysql");
        verify(containerLauncher).remove(errored.getValue().getContainerId());
        verify(containerLauncher, times(1)).remove(anyString());
        verify(portAllocator).release(List.of(30010, 30011));
    }

    @Test
    @DisplayName("镜像缺失时标记 ERROR，且不留下容器")
    void missingImageMarksEnvironmentErrorWithoutStartingContainers() {
        when(portAllocator.allocate(1)).thenReturn(List.of(30010));
        stubEnvironmentInsert();
        doThrow(new BusinessException(ErrorCode.DOCKER_ERROR, "本地缺少镜像 mysql:8.0"))
                .when(containerLauncher).ensureImage("mysql:8.0");

        BusinessException failure = catchThrowableOfType(
                () -> provisioner.provision(task(8L, "[\"mysql\"]")), BusinessException.class);

        assertThat(failure).isInstanceOf(ProvisioningFailedException.class);
        assertThat(((ProvisioningFailedException) failure).getEnvironmentId()).isEqualTo(42L);
        assertThat(failure.getMessage()).isEqualTo("本地缺少镜像 mysql:8.0");
        verify(containerLauncher, never()).start(any());
        verify(containerLauncher, never()).remove(any());
        verify(environmentMapper).markProvisionFailed(42L);
        verifyNoInteractions(containerMapper);
        verify(portAllocator).release(List.of(30010));
    }

    @Test
    @DisplayName("端口未就绪时清理容器并标记 ERROR")
    void readinessTimeoutCleansUp() {
        provisioningProperties.setReadyTimeout(Duration.ZERO);
        when(portAllocator.allocate(1)).thenReturn(List.of(30010));
        stubEnvironmentInsert();
        stubContainerInsert();
        when(containerLauncher.start(any())).thenReturn(new ContainerLauncher.StartedContainer("cid-mysql", false));
        when(containerLauncher.inspect("cid-mysql")).thenReturn(new ContainerLauncher.Snapshot(true, null));

        BusinessException failure = catchThrowableOfType(
                () -> provisioner.provision(task(8L, "mysql")), BusinessException.class);

        assertThat(failure).isInstanceOf(ProvisioningFailedException.class);
        assertThat(((ProvisioningFailedException) failure).getEnvironmentId()).isEqualTo(42L);
        assertThat(failure.getCode()).isEqualTo(ErrorCode.ENVIRONMENT_CREATE_FAILED.getCode());
        assertThat(failure.getMessage()).isEqualTo("依赖 mysql 未在超时时间内就绪");
        assertThat(failure.getMessage()).doesNotContain("\n");
        verify(containerLauncher).remove("cid-mysql");
        verify(environmentMapper).markProvisionFailed(42L);

        ArgumentCaptor<Container> errored = ArgumentCaptor.forClass(Container.class);
        verify(containerMapper).updateById(errored.capture());
        assertThat(errored.getValue().getStatus()).isEqualTo("ERROR");
        verify(readinessPause, never()).pause(any());
    }

    @Test
    @DisplayName("未释放的环境直接返回，不再启动容器")
    void returnsExistingEnvironmentWhenItIsStillHeld() {
        for (String status : List.of("CREATING", "READY", "BUSY", "RELEASING")) {
            Task task = task(3L, "[\"kafka\"]");
            task.setEnvironmentId(9L);
            Environment existing = new Environment();
            existing.setId(9L);
            existing.setStatus(status);
            when(environmentMapper.selectById(9L)).thenReturn(existing);

            assertThat(provisioner.provision(task)).isSameAs(existing);
        }
        verify(environmentMapper, never()).insert(any(Environment.class));
        verify(containerLauncher, never()).start(any());
        verify(portAllocator, never()).allocate(anyInt());
    }

    @Test
    @DisplayName("空闲、失败或保留中的旧环境不会被复用")
    void idleOrErrorEnvironmentIsNotReused() {
        when(environmentMapper.insert(any(Environment.class))).thenAnswer(invocation -> {
            Environment environment = invocation.getArgument(0);
            environment.setId(50L);
            return 1;
        });
        for (String status : List.of("IDLE", "ERROR", "RETAINED")) {
            Task task = task(3L, "[]");
            task.setEnvironmentId(9L);
            Environment previous = new Environment();
            previous.setId(9L);
            previous.setStatus(status);
            when(environmentMapper.selectById(9L)).thenReturn(previous);

            Environment created = provisioner.provision(task);
            assertThat(created).isNotSameAs(previous);
            assertThat(created.getStatus()).isEqualTo("READY");
        }
        verifyNoInteractions(containerLauncher);
    }

    @Test
    @DisplayName("未知依赖在创建环境之前失败")
    void unknownDependencyDoesNotCreateEnvironment() {
        BusinessException failure = catchThrowableOfType(
                () -> provisioner.provision(task(1L, "[\"oracle\"]")), BusinessException.class);

        assertThat(failure).isNotNull();
        assertThat(failure).isNotInstanceOf(ProvisioningFailedException.class);
        assertThat(failure.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.getCode());
        assertThat(failure.getMessage()).isEqualTo(
                "未知依赖: oracle，可选种类: mysql, redis, postgres, mongodb, rabbitmq, nginx, kafka");
        verify(environmentMapper, never()).insert(any(Environment.class));
        verifyNoInteractions(portAllocator);
        verifyNoInteractions(containerLauncher);
    }

    @Test
    @DisplayName("任务为空时拒绝供给")
    void rejectsMissingTask() {
        assertThatThrownBy(() -> provisioner.provision(null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("任务不能为空");
        Task task = new Task();
        assertThatThrownBy(() -> provisioner.provision(task))
                .isInstanceOf(BusinessException.class)
                .hasMessage("任务不能为空");
    }

    @Test
    @DisplayName("启动失败时带上依赖名和原因，去掉守护进程地址，并带上环境 ID")
    void startFailureCarriesEnvironmentIdDependencyAndCause() {
        when(portAllocator.allocate(1)).thenReturn(List.of(30010));
        stubEnvironmentInsert();
        when(containerLauncher.start(any())).thenThrow(new BusinessException(
                ErrorCode.DOCKER_ERROR,
                "启动容器失败: failed to set up container networking: network x not found"
                        + " unix:///var/run/docker.sock tcp://10.1.2.3:2375 10.9.8.7:2375\n"
                        + "dial unix:///var/run/docker.sock"));

        ProvisioningFailedException failure = catchThrowableOfType(
                () -> provisioner.provision(task(8L, "redis")), ProvisioningFailedException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getEnvironmentId()).isEqualTo(42L);
        assertThat(failure.getCode()).isEqualTo(ErrorCode.DOCKER_ERROR.getCode());
        assertThat(failure.getMessage()).isEqualTo(
                "启动 redis 失败: failed to set up container networking: network x not found");
        assertThat(failure.getMessage())
                .doesNotContain("unix://")
                .doesNotContain("tcp://")
                .doesNotContain("10.1.2.3")
                .doesNotContain("10.9.8.7")
                .doesNotContain("2375")
                .doesNotContain("\n")
                .doesNotContain("\tat");
        verify(environmentMapper).markProvisionFailed(42L);
        verify(containerLauncher, never()).remove(anyString());
    }

    @Test
    @DisplayName("就绪超时带上最后 20 行容器日志和环境 ID")
    void readinessTimeoutIncludesContainerLogs() {
        provisioningProperties.setReadyTimeout(Duration.ZERO);
        when(portAllocator.allocate(1)).thenReturn(List.of(30010));
        stubEnvironmentInsert();
        stubContainerInsert();
        when(containerLauncher.start(any())).thenReturn(new ContainerLauncher.StartedContainer("cid-mysql", false));
        when(containerLauncher.inspect("cid-mysql")).thenReturn(new ContainerLauncher.Snapshot(true, null));
        StringBuilder logs = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            logs.append(String.format("line-%02d%n", i));
        }
        when(containerLauncher.recentLogs("cid-mysql", 20)).thenReturn(logs.toString());

        ProvisioningFailedException failure = catchThrowableOfType(
                () -> provisioner.provision(task(8L, "mysql")), ProvisioningFailedException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getEnvironmentId()).isEqualTo(42L);
        assertThat(failure.getCode()).isEqualTo(ErrorCode.ENVIRONMENT_CREATE_FAILED.getCode());
        assertThat(failure.getMessage()).startsWith("依赖 mysql 未在超时时间内就绪\nline-06\n");
        assertThat(failure.getMessage()).contains("line-25");
        assertThat(failure.getMessage()).doesNotContain("line-05").doesNotContain("\tat");
        assertThat(failure.getMessage().split("\n")).hasSize(21);
        verify(containerLauncher).recentLogs("cid-mysql", 20);
        verify(containerLauncher).remove("cid-mysql");
        verify(environmentMapper).markProvisionFailed(42L);
    }

    @Test
    @DisplayName("空环境落库后失败时异常带上环境 ID")
    void emptyEnvironmentFailureCarriesEnvironmentId() {
        when(environmentMapper.insert(any(Environment.class))).thenAnswer(invocation -> {
            Environment environment = invocation.getArgument(0);
            environment.setId(42L);
            return 1;
        });
        when(environmentMapper.updateById(any(Environment.class))).thenThrow(new RuntimeException("db"));

        ProvisioningFailedException failure = catchThrowableOfType(
                () -> provisioner.provision(task(3L, "[]")), ProvisioningFailedException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getEnvironmentId()).isEqualTo(42L);
        assertThat(failure.getCode()).isEqualTo(ErrorCode.ENVIRONMENT_CREATE_FAILED.getCode());
        assertThat(failure.getMessage()).isEqualTo("环境创建失败");
        verify(environmentMapper).markProvisionFailed(42L);
        verifyNoInteractions(containerLauncher);
    }

    @Test
    @DisplayName("Kafka 对外地址在分配端口后替换为主机和主机端口")
    void kafkaAdvertisedListenerUsesAllocatedHostPort() {
        provisioningProperties.setHost("127.0.0.1");
        when(portAllocator.allocate(1)).thenReturn(List.of(36210));
        stubEnvironmentInsert();
        stubContainerInsert();
        when(containerLauncher.start(any())).thenReturn(new ContainerLauncher.StartedContainer("cid-kafka", false));
        when(containerLauncher.inspect("cid-kafka")).thenReturn(new ContainerLauncher.Snapshot(true, null));
        when(tcpProbe.accepts("127.0.0.1", 36210)).thenReturn(true);

        Environment created = provisioner.provision(task(11L, "[\"kafka\"]"));

        ArgumentCaptor<ContainerLauncher.Launch> launches = ArgumentCaptor.forClass(ContainerLauncher.Launch.class);
        verify(containerLauncher).start(launches.capture());
        ContainerLauncher.Launch launch = launches.getValue();
        assertThat(launch.image()).isEqualTo("apache/kafka:3.7.0");
        assertThat(launch.containerPort()).isEqualTo(9092);
        assertThat(launch.hostPort()).isEqualTo(36210);
        assertThat(launch.env())
                .containsEntry("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093")
                .containsEntry("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://127.0.0.1:36210")
                .containsEntry("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@127.0.0.1:9093");
        assertThat(created.getComposeContent())
                .contains("36210:9092")
                .contains("PLAINTEXT://127.0.0.1:36210")
                .doesNotContain("${HOST}");
        ArgumentCaptor<Container> containers = ArgumentCaptor.forClass(Container.class);
        verify(containerMapper).insert(containers.capture());
        assertThat(containers.getValue().getPortMapping()).isEqualTo("9092:36210");
    }

    private void stubEnvironmentInsert() {
        when(environmentMapper.insert(any(Environment.class))).thenAnswer(invocation -> {
            Environment environment = invocation.getArgument(0);
            environment.setId(42L);
            return 1;
        });
    }

    private void stubContainerInsert() {
        AtomicInteger ids = new AtomicInteger(1);
        when(containerMapper.insert(any(Container.class))).thenAnswer(invocation -> {
            Container container = invocation.getArgument(0);
            container.setId((long) ids.getAndIncrement());
            return 1;
        });
    }

    private Task task(Long id, String dependencies) {
        Task task = new Task();
        task.setId(id);
        task.setDependencies(dependencies);
        return task;
    }
}
