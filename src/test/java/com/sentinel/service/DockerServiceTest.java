package com.sentinel.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.HealthState;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.DockerProperties;
import com.sentinel.domain.Container;
import com.sentinel.mapper.ContainerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DockerService 单元测试
 * 使用 Mockito 模拟 DockerClient 与 ContainerMapper
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DockerService 单元测试")
public class DockerServiceTest {

    @Mock
    private DockerClient dockerClient;

    @Mock
    private ContainerMapper containerMapper;

    private DockerProperties dockerProperties;
    private DockerService dockerService;

    @BeforeEach
    public void setUp() {
        dockerProperties = new DockerProperties();
        dockerProperties.setResponseTimeout(1);
        dockerService = new DockerService(dockerClient, containerMapper, dockerProperties);
    }

    @Test
    @DisplayName("启动失败时强制删除已创建的容器")
    public void shouldForceRemoveContainer_whenStartFails() {
        CreateContainerCmd createCmd = stubCreateCmd();
        when(createCmd.exec()).thenReturn(created("cid-start-fail"));

        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("cid-start-fail")).thenReturn(startCmd);
        doThrow(new RuntimeException("start failed")).when(startCmd).exec();

        RemoveContainerCmd removeCmd = stubForceRemove("cid-start-fail", null);

        assertThatThrownBy(() -> dockerService.createAndStartContainer(1L, "nginx:1.25", "web", 8080, 80))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("start failed");

        verify(dockerClient).removeContainerCmd("cid-start-fail");
        verify(removeCmd).withForce(eq(Boolean.TRUE));
        verify(removeCmd).exec();
        verify(containerMapper, never()).insert(any(Container.class));
    }

    @Test
    @DisplayName("入库失败时强制删除已创建的容器")
    public void shouldForceRemoveContainer_whenInsertFails() {
        CreateContainerCmd createCmd = stubCreateCmd();
        when(createCmd.exec()).thenReturn(created("cid-insert-fail"));
        stubStartSuccess("cid-insert-fail");
        when(containerMapper.insert(any(Container.class))).thenThrow(new RuntimeException("insert failed"));

        RemoveContainerCmd removeCmd = stubForceRemove("cid-insert-fail", null);

        assertThatThrownBy(() -> dockerService.createAndStartContainer(1L, "mysql:8.0", "db", 33061, 3306))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("insert failed");

        verify(dockerClient).removeContainerCmd("cid-insert-fail");
        verify(removeCmd).withForce(eq(Boolean.TRUE));
        verify(removeCmd).exec();
    }

    @Test
    @DisplayName("清理容器失败时仍抛出原始异常")
    public void shouldKeepOriginalError_whenCleanupFails() {
        CreateContainerCmd createCmd = stubCreateCmd();
        when(createCmd.exec()).thenReturn(created("cid-cleanup-fail"));
        stubStartSuccess("cid-cleanup-fail");
        when(containerMapper.insert(any(Container.class))).thenThrow(new RuntimeException("insert failed"));
        stubForceRemove("cid-cleanup-fail", new RuntimeException("cleanup failed"));

        assertThatThrownBy(() -> dockerService.createAndStartContainer(1L, "redis:7", "cache", 16379, 6379))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("insert failed")
                .hasMessageNotContaining("cleanup failed");
    }

    @Test
    @DisplayName("镜像创建失败且尚未拿到容器 ID 时不删除容器")
    public void shouldNotRemoveContainer_whenCreateFailsBeforeId() {
        CreateContainerCmd createCmd = stubCreateCmd();
        when(createCmd.exec()).thenThrow(new RuntimeException("image missing"));

        assertThatThrownBy(() -> dockerService.createAndStartContainer(1L, "missing:latest", "x", 1, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("image missing");

        verify(dockerClient, never()).removeContainerCmd(nullable(String.class));
    }

    @Test
    @DisplayName("创建容器时把内存、CPU 和网络写入 HostConfig")
    public void shouldApplyMemoryCpuAndNetworkToHostConfig() {
        DockerProperties.ContainerConfig containerConfig = dockerProperties.getContainer();
        containerConfig.setMemoryLimit(2048);
        containerConfig.setCpuLimit(1.5);
        containerConfig.setDefaultNetwork("sentinel-dev-network");
        containerConfig.setAutoRemove(true);
        containerConfig.setRestartPolicy("unless-stopped");

        CreateContainerCmd createCmd = stubRunningContainer("cid-limits");

        Container created = dockerService.createAndStartContainer(
                9L, "redis:7", "sentinel-redis", 18080, 8080);

        ArgumentCaptor<HostConfig> hostConfigCaptor = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfigCaptor.capture());
        verify(createCmd).withName("sentinel-redis");

        HostConfig hostConfig = hostConfigCaptor.getValue();
        assertThat(hostConfig.getMemory()).isEqualTo(2048L * 1024L * 1024L);
        assertThat(hostConfig.getNanoCPUs()).isEqualTo(1_500_000_000L);
        assertThat(hostConfig.getNetworkMode()).isEqualTo("sentinel-dev-network");
        assertThat(hostConfig.getAutoRemove()).isTrue();
        assertThat(hostConfig.getRestartPolicy().getName()).isEqualTo("unless-stopped");
        assertThat(hostConfig.getPortBindings()).isNotNull();
        assertThat(hostConfig.getPortBindings().getBindings()).isNotEmpty();

        assertThat(created.getContainerId()).isEqualTo("cid-limits");
        assertThat(created.getStatus()).isEqualTo("RUNNING");
        assertThat(created.getHealthStatus()).isEqualTo("UNKNOWN");
        assertThat(created.getPortMapping()).isEqualTo("8080:18080");
        verify(dockerClient, never()).removeContainerCmd(nullable(String.class));
    }

    @Test
    @DisplayName("未配置的网络、CPU 和重启策略不写入 HostConfig")
    public void shouldSkipUnsetContainerResources() {
        DockerProperties.ContainerConfig containerConfig = dockerProperties.getContainer();
        containerConfig.setMemoryLimit(128);
        containerConfig.setCpuLimit(null);
        containerConfig.setDefaultNetwork(" ");
        containerConfig.setRestartPolicy("");
        containerConfig.setAutoRemove(null);

        CreateContainerCmd createCmd = stubRunningContainer("cid-partial");
        dockerService.createAndStartContainer(1L, "nginx:1.25", "web", 8080, 80);

        ArgumentCaptor<HostConfig> hostConfigCaptor = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfigCaptor.capture());
        HostConfig hostConfig = hostConfigCaptor.getValue();
        assertThat(hostConfig.getMemory()).isEqualTo(128L * 1024L * 1024L);
        assertThat(hostConfig.getNanoCPUs()).isNull();
        assertThat(hostConfig.getNetworkMode()).isNull();
        assertThat(hostConfig.getRestartPolicy()).isNull();
        assertThat(hostConfig.getAutoRemove()).isFalse();
    }

    @Test
    @DisplayName("运行中但没有健康检查时返回 UNKNOWN")
    public void shouldReturnUnknown_whenRunningWithoutHealthcheck() {
        InspectContainerResponse.ContainerState state = stubInspect("cid-no-health");
        when(state.getRunning()).thenReturn(true);
        when(state.getHealth()).thenReturn(null);

        assertThat(dockerService.checkContainerHealth("cid-no-health")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("Docker healthy 映射为 HEALTHY")
    public void shouldMapHealthyStatusToUpperCase() {
        InspectContainerResponse.ContainerState state = stubInspect("cid-healthy");
        HealthState dockerHealth = health("healthy");
        when(state.getRunning()).thenReturn(true);
        when(state.getHealth()).thenReturn(dockerHealth);

        assertThat(dockerService.checkContainerHealth("cid-healthy")).isEqualTo("HEALTHY");
    }

    @Test
    @DisplayName("Docker unhealthy 映射为 UNHEALTHY")
    public void shouldMapUnhealthyStatusToUpperCase() {
        InspectContainerResponse.ContainerState state = stubInspect("cid-unhealthy");
        HealthState dockerHealth = health("unhealthy");
        when(state.getRunning()).thenReturn(true);
        when(state.getHealth()).thenReturn(dockerHealth);

        assertThat(dockerService.checkContainerHealth("cid-unhealthy")).isEqualTo("UNHEALTHY");
    }

    @Test
    @DisplayName("Docker starting 映射为 STARTING")
    public void shouldMapStartingStatusToUpperCase() {
        InspectContainerResponse.ContainerState state = stubInspect("cid-starting");
        HealthState dockerHealth = health("starting");
        when(state.getRunning()).thenReturn(true);
        when(state.getHealth()).thenReturn(dockerHealth);

        assertThat(dockerService.checkContainerHealth("cid-starting")).isEqualTo("STARTING");
    }

    @Test
    @DisplayName("容器未运行时返回 UNHEALTHY")
    public void shouldReturnUnhealthy_whenContainerNotRunning() {
        InspectContainerResponse.ContainerState state = stubInspect("cid-stopped");
        when(state.getRunning()).thenReturn(false);

        assertThat(dockerService.checkContainerHealth("cid-stopped")).isEqualTo("UNHEALTHY");
    }

    @Test
    @DisplayName("running 为空时按未运行返回 UNHEALTHY")
    public void shouldReturnUnhealthy_whenRunningIsNull() {
        InspectContainerResponse.ContainerState state = stubInspect("cid-null-running");
        when(state.getRunning()).thenReturn(null);

        assertThat(dockerService.checkContainerHealth("cid-null-running")).isEqualTo("UNHEALTHY");
    }

    @Test
    @DisplayName("检查健康状态失败时返回 UNKNOWN")
    public void shouldReturnUnknown_whenInspectFails() {
        when(dockerClient.inspectContainerCmd("cid-inspect-fail"))
                .thenThrow(new RuntimeException("inspect failed"));

        assertThat(dockerService.checkContainerHealth("cid-inspect-fail")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("停止容器时按容器 ID 更新对应记录")
    public void shouldStopContainerByContainerId() {
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(dockerClient.stopContainerCmd("cid-9")).thenReturn(stopCmd);
        doNothing().when(stopCmd).exec();

        Container row = new Container();
        row.setId(42L);
        row.setContainerId("cid-9");
        row.setStatus("RUNNING");
        when(containerMapper.findByContainerId("cid-9")).thenReturn(row);

        dockerService.stopContainer("cid-9");

        ArgumentCaptor<Container> captor = ArgumentCaptor.forClass(Container.class);
        verify(containerMapper).findByContainerId("cid-9");
        verify(containerMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(42L);
        assertThat(captor.getValue().getStatus()).isEqualTo("STOPPED");
        assertThat(captor.getValue().getStoppedAt()).isNotNull();
        verify(containerMapper, never()).selectList(nullable(Wrapper.class));
    }

    @Test
    @DisplayName("删除容器时按容器 ID 删除对应记录")
    public void shouldRemoveContainerByContainerId() {
        stubForceRemove("cid-rm", null);

        Container row = new Container();
        row.setId(7L);
        row.setContainerId("cid-rm");
        when(containerMapper.findByContainerId("cid-rm")).thenReturn(row);

        dockerService.removeContainer("cid-rm");

        verify(containerMapper).findByContainerId("cid-rm");
        verify(containerMapper).deleteById(7L);
        verify(containerMapper, never()).selectList(nullable(Wrapper.class));
    }

    @Test
    @DisplayName("容器日志按 UTF-8 解码并忽略空 payload")
    public void shouldDecodeContainerLogsAsUtf8AndSkipNullPayload() throws Exception {
        LogContainerCmd logCmd = stubLogCmd("cid-logs");
        Closeable stream = mock(Closeable.class);
        doAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onStart(stream);
            callback.onNext(null);
            callback.onNext(new Frame(StreamType.STDOUT, "甲".getBytes(StandardCharsets.UTF_8)));
            callback.onNext(new Frame(StreamType.STDERR, null));
            callback.onNext(new Frame(StreamType.STDOUT, "乙".getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        }).when(logCmd).exec(any(ResultCallback.class));

        String logs = dockerService.getContainerLogs("cid-logs", 20);

        assertThat(logs).isEqualTo("甲乙");
        verify(logCmd).withStdOut(eq(Boolean.TRUE));
        verify(logCmd).withStdErr(eq(Boolean.TRUE));
        verify(logCmd).withTail(eq(Integer.valueOf(20)));
        verify(stream).close();
    }

    @Test
    @DisplayName("拉取日志失败时仍关闭 ResultCallback")
    public void shouldCloseLogCallback_whenLogExecFails() throws Exception {
        LogContainerCmd logCmd = stubLogCmd("cid-log-fail");
        Closeable stream = mock(Closeable.class);
        doAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onStart(stream);
            throw new RuntimeException("log exec failed");
        }).when(logCmd).exec(any(ResultCallback.class));

        assertThatThrownBy(() -> dockerService.getContainerLogs("cid-log-fail", 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("log exec failed");

        verify(stream).close();
    }

    @Test
    @DisplayName("日志等待使用 responseTimeout，超时则失败")
    public void shouldFail_whenLogAwaitTimesOut() {
        dockerProperties.setResponseTimeout(0);
        LogContainerCmd logCmd = stubLogCmd("cid-log-timeout");
        doAnswer(invocation -> invocation.getArgument(0))
                .when(logCmd).exec(any(ResultCallback.class));

        assertThatThrownBy(() -> dockerService.getContainerLogs("cid-log-timeout", 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超时")
                .hasMessageContaining("0");
    }

    private CreateContainerCmd stubCreateCmd() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCmd);
        when(createCmd.withName(anyString())).thenReturn(createCmd);
        when(createCmd.withExposedPorts(any(ExposedPort[].class))).thenReturn(createCmd);
        when(createCmd.withHostConfig(any(HostConfig.class))).thenReturn(createCmd);
        return createCmd;
    }

    private CreateContainerCmd stubRunningContainer(String containerId) {
        CreateContainerCmd createCmd = stubCreateCmd();
        when(createCmd.exec()).thenReturn(created(containerId));
        stubStartSuccess(containerId);
        when(containerMapper.insert(any(Container.class))).thenReturn(1);
        return createCmd;
    }

    private void stubStartSuccess(String containerId) {
        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd(containerId)).thenReturn(startCmd);
        doNothing().when(startCmd).exec();
    }

    private RemoveContainerCmd stubForceRemove(String containerId, RuntimeException execError) {
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd(containerId)).thenReturn(removeCmd);
        when(removeCmd.withForce(eq(Boolean.TRUE))).thenReturn(removeCmd);
        if (execError == null) {
            doNothing().when(removeCmd).exec();
        } else {
            doThrow(execError).when(removeCmd).exec();
        }
        return removeCmd;
    }

    private CreateContainerResponse created(String containerId) {
        CreateContainerResponse response = new CreateContainerResponse();
        response.setId(containerId);
        return response;
    }

    private InspectContainerResponse.ContainerState stubInspect(String containerId) {
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(dockerClient.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(response);
        when(response.getState()).thenReturn(state);
        return state;
    }

    private HealthState health(String status) {
        HealthState healthState = mock(HealthState.class);
        when(healthState.getStatus()).thenReturn(status);
        return healthState;
    }

    private LogContainerCmd stubLogCmd(String containerId) {
        LogContainerCmd logCmd = mock(LogContainerCmd.class);
        when(dockerClient.logContainerCmd(containerId)).thenReturn(logCmd);
        when(logCmd.withStdOut(eq(Boolean.TRUE))).thenReturn(logCmd);
        when(logCmd.withStdErr(eq(Boolean.TRUE))).thenReturn(logCmd);
        when(logCmd.withTail(any(Integer.class))).thenReturn(logCmd);
        return logCmd;
    }
}
