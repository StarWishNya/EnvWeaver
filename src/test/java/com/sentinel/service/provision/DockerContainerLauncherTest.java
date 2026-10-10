package com.sentinel.service.provision;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateNetworkCmd;
import com.github.dockerjava.api.command.CreateNetworkResponse;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.InspectNetworkCmd;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HealthCheck;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.DockerProperties;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.enums.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DockerClient 命令链：拉取、资源限制、强制删除、健康检查。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("容器启动器")
class DockerContainerLauncherTest {

    @Mock
    private DockerClient dockerClient;

    private DockerProperties dockerProperties;
    private ProvisioningProperties provisioningProperties;
    private DockerContainerLauncher launcher;
    private InspectNetworkCmd inspectNetwork;

    @BeforeEach
    void setUp() {
        dockerProperties = new DockerProperties();
        provisioningProperties = new ProvisioningProperties();
        launcher = new DockerContainerLauncher(dockerClient, dockerProperties, provisioningProperties);
        inspectNetwork = mock(InspectNetworkCmd.class);
        lenient().when(dockerClient.inspectNetworkCmd()).thenReturn(inspectNetwork);
        lenient().when(inspectNetwork.withNetworkId(anyString())).thenReturn(inspectNetwork);
        lenient().when(inspectNetwork.exec()).thenReturn(null);
    }

    @Test
    @DisplayName("拆分镜像仓库和标签时保留仓库端口")
    void parsesImageRepositoryAndTag() {
        DockerContainerLauncher.ImageRef tagged = DockerContainerLauncher.parseImageRef("mysql:8.0");
        assertThat(tagged.repository()).isEqualTo("mysql");
        assertThat(tagged.tag()).isEqualTo("8.0");

        DockerContainerLauncher.ImageRef registry = DockerContainerLauncher.parseImageRef("localhost:5000/team/mysql:8.0");
        assertThat(registry.repository()).isEqualTo("localhost:5000/team/mysql");
        assertThat(registry.tag()).isEqualTo("8.0");

        DockerContainerLauncher.ImageRef latest = DockerContainerLauncher.parseImageRef("nginx");
        assertThat(latest.repository()).isEqualTo("nginx");
        assertThat(latest.tag()).isNull();
    }

    @Test
    @DisplayName("本地已有镜像时不拉取")
    void skipsPullWhenImageExists() {
        InspectImageCmd inspect = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("redis:7-alpine")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(new InspectImageResponse());

        launcher.ensureImage("redis:7-alpine");

        verify(dockerClient, never()).pullImageCmd(anyString());
    }

    @Test
    @DisplayName("禁止拉取时，缺失镜像的错误不带守护进程地址")
    void missingImageWithoutPullDoesNotLeakDockerHost() {
        InspectImageCmd inspect = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("mysql:8.0")).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("Get http://10.1.2.3:2375/v1.41/images/mysql"));
        provisioningProperties.setPullMissing(false);

        BusinessException failure = catchThrowableOfType(() -> launcher.ensureImage("mysql:8.0"), BusinessException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getCode()).isEqualTo(ErrorCode.DOCKER_ERROR.getCode());
        assertThat(failure.getMessage()).isEqualTo("本地缺少镜像 mysql:8.0");
        assertThat(failure.getMessage()).doesNotContain("10.1.2.3");
        verify(dockerClient, never()).pullImageCmd(anyString());
    }

    @Test
    @DisplayName("本地没有镜像时按仓库和标签拉取")
    void pullsMissingImageWithRepositoryAndTag() {
        stubImageMissing("mysql:8.0");
        PullImageCmd pull = mock(PullImageCmd.class);
        when(dockerClient.pullImageCmd("mysql")).thenReturn(pull);
        when(pull.withTag("8.0")).thenReturn(pull);
        completePull(pull);

        launcher.ensureImage("mysql:8.0");

        verify(pull).withTag("8.0");
        verify(pull).exec(any(ResultCallback.class));
    }

    @Test
    @DisplayName("拉取失败时错误信息不带套接字路径")
    void pullFailureHidesDockerEndpoint() {
        stubImageMissing("mysql:8.0");
        PullImageCmd pull = mock(PullImageCmd.class);
        when(dockerClient.pullImageCmd("mysql")).thenReturn(pull);
        when(pull.withTag("8.0")).thenReturn(pull);
        when(pull.exec(any(ResultCallback.class))).thenThrow(new RuntimeException("dial unix:///var/run/docker.sock"));

        BusinessException failure = catchThrowableOfType(() -> launcher.ensureImage("mysql:8.0"), BusinessException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).isEqualTo("拉取镜像失败: mysql:8.0");
        assertThat(failure.getMessage()).doesNotContain("docker.sock");
    }

    @Test
    @DisplayName("拉取超时")
    void pullTimesOut() {
        provisioningProperties.setPullTimeout(Duration.ofMillis(30));
        stubImageMissing("nginx:alpine");
        PullImageCmd pull = mock(PullImageCmd.class);
        when(dockerClient.pullImageCmd("nginx")).thenReturn(pull);
        when(pull.withTag("alpine")).thenReturn(pull);
        when(pull.exec(any(ResultCallback.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BusinessException failure = catchThrowableOfType(() -> launcher.ensureImage("nginx:alpine"), BusinessException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).isEqualTo("拉取镜像超时: nginx:alpine");
    }

    @Test
    @DisplayName("创建容器时写入内存、CPU、网络、标签和环境变量")
    void appliesHostLimitsLabelsAndEnv() {
        dockerProperties.getContainer().setMemoryLimit(2048);
        dockerProperties.getContainer().setCpuLimit(1.5);
        dockerProperties.getContainer().setDefaultNetwork("sentinel-test-network");
        CreateContainerCmd create = stubCreate("mysql:8.0", "cid-mysql");
        stubStart("cid-mysql");
        stubImageWithoutHealthcheck("mysql:8.0");

        ContainerLauncher.StartedContainer started = launcher.start(new ContainerLauncher.Launch(
                "mysql:8.0",
                "sentinel-env-env-abcd1234-mysql",
                3306,
                33061,
                Map.of("MYSQL_DATABASE", "test", "MYSQL_ROOT_PASSWORD", DependencyCatalog.DEFAULT_PASSWORD),
                Map.of("sentinel.env", "env-abcd1234", "sentinel.task", "9")));

        assertThat(started.containerId()).isEqualTo("cid-mysql");
        assertThat(started.healthcheckDefined()).isFalse();
        verify(create).withName("sentinel-env-env-abcd1234-mysql");

        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(create).withHostConfig(hostConfig.capture());
        assertThat(hostConfig.getValue().getMemory()).isEqualTo(2048L * 1024L * 1024L);
        assertThat(hostConfig.getValue().getNanoCPUs()).isEqualTo(1_500_000_000L);
        assertThat(hostConfig.getValue().getNetworkMode()).isEqualTo("sentinel-test-network");
        assertThat(hostConfig.getValue().getRestartPolicy().getName()).isEqualTo("unless-stopped");
        assertThat(hostConfig.getValue().getAutoRemove()).isFalse();
        Ports.Binding[] bindings = hostConfig.getValue().getPortBindings().getBindings().get(ExposedPort.tcp(3306));
        assertThat(bindings).isNotEmpty();
        assertThat(bindings[0].getHostPortSpec()).isEqualTo("33061");
        assertThat(bindings[0].getHostIp()).isEqualTo("127.0.0.1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
        verify(create).withLabels(labels.capture());
        assertThat(labels.getValue()).containsEntry("sentinel.env", "env-abcd1234").containsEntry("sentinel.task", "9");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        verify(create).withEnv(env.capture());
        assertThat(env.getValue()).contains(
                "MYSQL_DATABASE=test",
                "MYSQL_ROOT_PASSWORD=" + DependencyCatalog.DEFAULT_PASSWORD);
    }

    @Test
    @DisplayName("镜像定义了健康检查时启动结果标为需要等待 healthy")
    void detectsImageHealthcheck() {
        CreateContainerCmd create = stubCreate("mysql:8.0", "cid-mysql");
        stubStart("cid-mysql");
        InspectImageCmd inspect = mock(InspectImageCmd.class);
        InspectImageResponse image = mock(InspectImageResponse.class);
        ContainerConfig config = mock(ContainerConfig.class);
        when(dockerClient.inspectImageCmd("mysql:8.0")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(image);
        when(image.getConfig()).thenReturn(config);
        when(config.getHealthcheck()).thenReturn(new HealthCheck().withTest(List.of("CMD", "mysqladmin", "ping")));

        ContainerLauncher.StartedContainer started = launcher.start(launchWithoutEnv());

        assertThat(started.healthcheckDefined()).isTrue();
        verify(create).exec();
    }

    @Test
    @DisplayName("健康检查为 NONE 时不当作需要等待")
    void noneHealthcheckIsIgnored() {
        stubCreate("nginx:alpine", "cid-nginx");
        stubStart("cid-nginx");
        InspectImageCmd inspect = mock(InspectImageCmd.class);
        InspectImageResponse image = mock(InspectImageResponse.class);
        ContainerConfig config = mock(ContainerConfig.class);
        when(dockerClient.inspectImageCmd("nginx:alpine")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(image);
        when(image.getConfig()).thenReturn(config);
        when(config.getHealthcheck()).thenReturn(new HealthCheck().withTest(List.of("NONE")));

        ContainerLauncher.StartedContainer started = launcher.start(new ContainerLauncher.Launch(
                "nginx:alpine", "web", 80, 30080, Map.of(), Map.of("sentinel.env", "env-1")));

        assertThat(started.healthcheckDefined()).isFalse();
    }

    @Test
    @DisplayName("启动失败时强制删除已创建容器，错误信息不带异常原文")
    void startFailureForceRemovesContainer() {
        stubCreate("redis:7-alpine", "cid-redis");
        StartContainerCmd start = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("cid-redis")).thenReturn(start);
        doThrow(new RuntimeException("http://docker.internal/start")).when(start).exec();
        RemoveContainerCmd remove = stubForceRemove("cid-redis");

        BusinessException failure = catchThrowableOfType(() -> launcher.start(new ContainerLauncher.Launch(
                "redis:7-alpine", "cache", 6379, 36379, Map.of(), Map.of())), BusinessException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).isEqualTo("启动容器失败");
        assertThat(failure.getMessage()).doesNotContain("docker.internal");
        verify(remove).withForce(eq(Boolean.TRUE));
        verify(remove).exec();
    }

    @Test
    @DisplayName("按容器 ID 强制删除，容器已不存在视为成功")
    void removesContainerByForceAndIgnoresMissingContainer() {
        RemoveContainerCmd remove = stubForceRemove("cid-1");
        launcher.remove("cid-1");
        verify(remove).withForce(eq(Boolean.TRUE));

        RemoveContainerCmd missing = stubForceRemove("cid-missing");
        doThrow(new NotFoundException("gone")).when(missing).exec();
        assertThatCode(() -> launcher.remove("cid-missing")).doesNotThrowAnyException();

        launcher.remove(null);
        launcher.remove("  ");
        verify(dockerClient, never()).removeContainerCmd(eq("  "));
    }

    @Test
    @DisplayName("检查容器运行状态和健康检查")
    void inspectsRunningAndHealth() {
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        com.github.dockerjava.api.command.HealthState health = mock(com.github.dockerjava.api.command.HealthState.class);
        when(dockerClient.inspectContainerCmd("cid-1")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        when(response.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(true);
        when(state.getHealth()).thenReturn(health);
        when(health.getStatus()).thenReturn("healthy");

        ContainerLauncher.Snapshot snapshot = launcher.inspect("cid-1");

        assertThat(snapshot.running()).isTrue();
        assertThat(snapshot.healthStatus()).isEqualTo("healthy");
    }

    @Test
    @DisplayName("容器不存在时返回短错误")
    void inspectMissingContainer() {
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("cid-missing")).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("http://10.0.0.8:2375/containers/cid-missing"));

        BusinessException failure = catchThrowableOfType(() -> launcher.inspect("cid-missing"), BusinessException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).isEqualTo("容器不存在");
        assertThat(failure.getMessage()).doesNotContain("10.0.0.8");
    }

    @Test
    @DisplayName("已有的用户网络只检查一次，不重复创建")
    void ensuresExistingUserNetworkOnce() {
        stubCreate("redis:7-alpine", "cid-redis");
        stubStart("cid-redis");
        stubImageWithoutHealthcheck("redis:7-alpine");

        launcher.start(redisLaunch("cache-a"));
        launcher.start(redisLaunch("cache-b"));

        verify(dockerClient, times(1)).inspectNetworkCmd();
        verify(inspectNetwork).withNetworkId("sentinel-network");
        verify(dockerClient, never()).createNetworkCmd();
    }

    @Test
    @DisplayName("用户网络不存在时创建 bridge 网络并打上托管标签")
    void createsMissingUserNetwork() {
        dockerProperties.getContainer().setDefaultNetwork("sentinel-dev-network");
        when(inspectNetwork.exec()).thenThrow(new NotFoundException("network sentinel-dev-network not found"));
        CreateNetworkCmd createNetwork = stubCreateNetwork("sentinel-dev-network", null);
        stubCreate("redis:7-alpine", "cid-redis");
        stubStart("cid-redis");
        stubImageWithoutHealthcheck("redis:7-alpine");

        ContainerLauncher.StartedContainer started = launcher.start(redisLaunch("cache"));

        assertThat(started.containerId()).isEqualTo("cid-redis");
        verify(createNetwork).withName("sentinel-dev-network");
        verify(createNetwork).withDriver("bridge");
        verify(createNetwork).withLabels(Map.of("sentinel.managed", "true"));
        verify(createNetwork).exec();

        launcher.start(redisLaunch("cache-again"));
        verify(dockerClient, times(1)).inspectNetworkCmd();
        verify(dockerClient, times(1)).createNetworkCmd();
    }

    @Test
    @DisplayName("创建网络撞上 409 时继续启动，并且不再重复创建")
    void ignoresNetworkConflict() {
        dockerProperties.getContainer().setDefaultNetwork("sentinel-dev-network");
        when(inspectNetwork.exec()).thenThrow(new NotFoundException("missing"));
        CreateNetworkCmd createNetwork = stubCreateNetwork("sentinel-dev-network",
                new ConflictException("Status 409: network already exists"));
        stubCreate("redis:7-alpine", "cid-redis");
        stubStart("cid-redis");
        stubImageWithoutHealthcheck("redis:7-alpine");

        assertThatCode(() -> launcher.start(redisLaunch("cache"))).doesNotThrowAnyException();
        launcher.start(redisLaunch("cache-again"));

        verify(createNetwork, times(1)).exec();
        verify(dockerClient, times(1)).createNetworkCmd();
        verify(dockerClient, times(1)).inspectNetworkCmd();
    }

    @Test
    @DisplayName("bridge、host、none、default 不检查也不创建")
    void skipsBuiltinNetworks() {
        stubCreate("nginx:alpine", "cid-nginx");
        stubStart("cid-nginx");
        stubImageWithoutHealthcheck("nginx:alpine");
        ContainerLauncher.Launch launch = new ContainerLauncher.Launch(
                "nginx:alpine", "web", 80, 30080, Map.of(), Map.of());

        for (String network : List.of("bridge", "HOST", "none", "default", "  ")) {
            dockerProperties.getContainer().setDefaultNetwork(network);
            launcher.start(launch);
        }

        verify(dockerClient, never()).inspectNetworkCmd();
        verify(dockerClient, never()).createNetworkCmd();
    }

    @Test
    @DisplayName("Docker 启动失败时带上第一行原因，并去掉守护进程地址")
    void startFailureIncludesSanitizedDockerCause() {
        dockerProperties.getContainer().setDefaultNetwork("bridge");
        stubCreate("redis:7-alpine", "cid-redis");
        StartContainerCmd start = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("cid-redis")).thenReturn(start);
        doThrow(new DockerException(
                "failed to set up container networking: network x not found"
                        + " unix:///var/run/docker.sock tcp://10.1.2.3:2375 10.9.8.7:2375\n"
                        + "dial unix:///var/run/docker.sock",
                500)).when(start).exec();
        stubForceRemove("cid-redis");

        BusinessException failure = catchThrowableOfType(() -> launcher.start(redisLaunch("cache")),
                BusinessException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getCode()).isEqualTo(ErrorCode.DOCKER_ERROR.getCode());
        assertThat(failure.getMessage()).isEqualTo(
                "启动容器失败: failed to set up container networking: network x not found");
        assertThat(failure.getMessage())
                .doesNotContain("unix://")
                .doesNotContain("tcp://")
                .doesNotContain("10.1.2.3")
                .doesNotContain("10.9.8.7")
                .doesNotContain("2375")
                .doesNotContain("\n");
        verify(dockerClient, never()).inspectNetworkCmd();
    }

    @Test
    @DisplayName("Docker 原因超过 200 字符时截断")
    void dockerCauseIsTruncatedTo200Chars() {
        dockerProperties.getContainer().setDefaultNetwork("bridge");
        stubCreate("redis:7-alpine", "cid-redis");
        StartContainerCmd start = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("cid-redis")).thenReturn(start);
        String cause = "c".repeat(180) + " unix:///var/run/docker.sock " + "d".repeat(80);
        doThrow(new DockerException(cause, 500)).when(start).exec();
        stubForceRemove("cid-redis");

        BusinessException failure = catchThrowableOfType(() -> launcher.start(redisLaunch("cache")),
                BusinessException.class);

        assertThat(failure).isNotNull();
        String detail = failure.getMessage().substring("启动容器失败: ".length());
        assertThat(detail).hasSize(200);
        assertThat(detail).startsWith("c".repeat(180));
        assertThat(failure.getMessage()).doesNotContain("unix://").doesNotContain("docker.sock");
    }

    @Test
    @DisplayName("未就绪容器只取最后 20 行日志，读失败时返回空")
    void recentLogsKeepsLastTwentyLines() {
        LogContainerCmd logCmd = mock(LogContainerCmd.class);
        when(dockerClient.logContainerCmd("cid-mysql")).thenReturn(logCmd);
        when(logCmd.withStdOut(Boolean.TRUE)).thenReturn(logCmd);
        when(logCmd.withStdErr(Boolean.TRUE)).thenReturn(logCmd);
        when(logCmd.withTail(20)).thenReturn(logCmd);
        StringBuilder payload = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            payload.append(String.format("line-%02d%n", i));
        }
        when(logCmd.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onNext(new Frame(StreamType.STDOUT, payload.toString().getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        });

        String logs = launcher.recentLogs("cid-mysql", 20);

        assertThat(logs).startsWith("line-06").endsWith("line-25");
        assertThat(logs).doesNotContain("line-05");
        assertThat(logs.split("\n")).hasSize(20);
        verify(logCmd).withTail(20);

        when(dockerClient.logContainerCmd("cid-down"))
                .thenThrow(new RuntimeException("unix:///var/run/docker.sock"));
        assertThat(launcher.recentLogs("cid-down", 20)).isEmpty();
        assertThat(launcher.recentLogs("  ", 20)).isEmpty();
        assertThat(launcher.recentLogs(null, 20)).isEmpty();
    }

    private void stubImageMissing(String image) {
        InspectImageCmd inspect = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd(image)).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("missing"));
    }

    private void completePull(PullImageCmd pull) {
        doAnswer(invocation -> {
            ResultCallback<?> callback = invocation.getArgument(0);
            callback.onComplete();
            return callback;
        }).when(pull).exec(any(ResultCallback.class));
    }

    private CreateContainerCmd stubCreate(String image, String containerId) {
        CreateContainerCmd create = mock(CreateContainerCmd.class);
        when(dockerClient.createContainerCmd(image)).thenReturn(create);
        when(create.withName(anyString())).thenReturn(create);
        when(create.withExposedPorts(any(ExposedPort[].class))).thenReturn(create);
        when(create.withHostConfig(any(HostConfig.class))).thenReturn(create);
        lenient().when(create.withEnv(anyList())).thenReturn(create);
        lenient().when(create.withLabels(anyMap())).thenReturn(create);
        CreateContainerResponse response = new CreateContainerResponse();
        response.setId(containerId);
        when(create.exec()).thenReturn(response);
        return create;
    }

    private void stubStart(String containerId) {
        StartContainerCmd start = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd(containerId)).thenReturn(start);
    }

    private void stubImageWithoutHealthcheck(String image) {
        InspectImageCmd inspect = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd(image)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(null);
    }

    private RemoveContainerCmd stubForceRemove(String containerId) {
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd(containerId)).thenReturn(remove);
        when(remove.withForce(eq(Boolean.TRUE))).thenReturn(remove);
        return remove;
    }

    private CreateNetworkCmd stubCreateNetwork(String name, RuntimeException execError) {
        CreateNetworkCmd createNetwork = mock(CreateNetworkCmd.class);
        when(dockerClient.createNetworkCmd()).thenReturn(createNetwork);
        when(createNetwork.withName(name)).thenReturn(createNetwork);
        when(createNetwork.withDriver("bridge")).thenReturn(createNetwork);
        when(createNetwork.withLabels(anyMap())).thenReturn(createNetwork);
        if (execError == null) {
            when(createNetwork.exec()).thenReturn(new CreateNetworkResponse());
        } else {
            when(createNetwork.exec()).thenThrow(execError);
        }
        return createNetwork;
    }

    private ContainerLauncher.Launch redisLaunch(String name) {
        return new ContainerLauncher.Launch(
                "redis:7-alpine", name, 6379, 36379, Map.of(), Map.of("sentinel.env", "env-1"));
    }

    private ContainerLauncher.Launch launchWithoutEnv() {
        return new ContainerLauncher.Launch(
                "mysql:8.0",
                "db",
                3306,
                33061,
                Map.of(),
                Map.of("sentinel.env", "env-1"));
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> anyList() {
        return org.mockito.ArgumentMatchers.anyList();
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> anyMap() {
        return org.mockito.ArgumentMatchers.anyMap();
    }
}
