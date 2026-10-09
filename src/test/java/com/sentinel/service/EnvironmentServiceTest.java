package com.sentinel.service;

import com.sentinel.config.BusinessException;
import com.sentinel.domain.Container;
import com.sentinel.domain.Environment;
import com.sentinel.dto.EnvironmentDTO;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.ContainerMapper;
import com.sentinel.mapper.EnvironmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EnvironmentService 单元测试。
 * Docker 与 Mapper 均使用 Mockito，不访问网络或真实 Docker。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnvironmentService 单元测试")
public class EnvironmentServiceTest {

    @Mock
    private EnvironmentMapper environmentMapper;

    @Mock
    private ContainerMapper containerMapper;

    @Mock
    private DockerService dockerService;

    private EnvironmentService environmentService;

    private final List<String> statusUpdates = new ArrayList<>();

    @BeforeEach
    public void setUp() {
        environmentService = new EnvironmentService(environmentMapper, containerMapper, dockerService);
        statusUpdates.clear();
        lenient().when(environmentMapper.updateById(any(Environment.class))).thenAnswer(invocation -> {
            Environment environment = invocation.getArgument(0);
            statusUpdates.add(environment.getStatus());
            return 1;
        });
    }

    @Test
    @DisplayName("列表按 id 倒序查询并解析 JSON")
    public void shouldListEnvironmentsAndParseJson() {
        Environment newer = environment("READY");
        newer.setId(5L);
        Environment older = environment("READY");
        older.setId(4L);
        older.setPortMappings("{not-json");
        when(environmentMapper.selectRecent(null, EnvironmentService.MAX_LIST_SIZE))
                .thenReturn(List.of(newer, older));

        List<EnvironmentDTO> result = environmentService.listEnvironments(null);

        assertThat(result).extracting(EnvironmentDTO::getId).containsExactly(5L, 4L);
        assertThat(result.get(0).getEnvCode()).isEqualTo("env-abc123");
        assertThat(result.get(0).getPortMappings()).containsEntry("mysql", 33061).containsEntry("redis", 63791);
        assertThat(result.get(0).getResourceUsage()).containsEntry("cpu", 0.5d).containsEntry("memory", 512);
        assertThat(result.get(0).getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 9, 8, 0));
        assertThat(result.get(1).getPortMappings()).isNull();
        assertThat(result.get(1).getResourceUsage()).containsEntry("memory", 512);
        verify(environmentMapper).selectRecent(null, 500);
    }

    @Test
    @DisplayName("按状态过滤，空白状态视为不过滤")
    public void shouldFilterByStatusAndIgnoreBlank() {
        when(environmentMapper.selectRecent("BUSY", 500)).thenReturn(List.of());
        when(environmentMapper.selectRecent(null, 500)).thenReturn(List.of());

        assertThat(environmentService.listEnvironments(" BUSY ")).isEmpty();
        assertThat(environmentService.listEnvironments("  ")).isEmpty();

        verify(environmentMapper).selectRecent("BUSY", 500);
        verify(environmentMapper).selectRecent(null, 500);
    }

    @Test
    @DisplayName("RETAINED 是合法过滤状态")
    public void shouldAcceptRetainedStatusFilter() {
        Environment retained = environment("RETAINED");
        when(environmentMapper.selectRecent("RETAINED", 500)).thenReturn(List.of(retained));

        List<EnvironmentDTO> result = environmentService.listEnvironments("RETAINED");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("RETAINED");
        verify(environmentMapper).selectRecent("RETAINED", 500);
    }

    @Test
    @DisplayName("非法状态返回 400 且不查库")
    public void shouldRejectUnknownStatus() {
        assertThatThrownBy(() -> environmentService.listEnvironments("running"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.VALIDATION_ERROR.getCode())
                .hasMessage("环境状态无效");

        verify(environmentMapper, never()).selectRecent(any(), eq(500));
    }

    @Test
    @DisplayName("详情解析端口与资源，空 JSON 为 null")
    public void shouldParseJsonOnGet() {
        Environment environment = environment("READY");
        environment.setPortMappings("{\"mysql\":33061.0}");
        environment.setResourceUsage("{\"cpu\":1,\"memory\":512,\"disk\":null}");
        when(environmentMapper.selectById(1L)).thenReturn(environment);

        EnvironmentDTO dto = environmentService.getEnvironment(1L);

        assertThat(dto.getPortMappings()).containsEntry("mysql", 33061);
        assertThat(dto.getResourceUsage())
                .containsEntry("cpu", 1)
                .containsEntry("memory", 512)
                .doesNotContainKey("disk");
        assertThat(dto.getComposeContent()).isEqualTo("services: {}\n");
        assertThat(dto.getAccessUrl()).isEqualTo("http://127.0.0.1:18080");
        assertThat(dto.getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 10, 9, 12, 0));
    }

    @Test
    @DisplayName("非法 JSON 与非对象变为 null，非数字 cpu 只置空该字段")
    public void shouldIgnoreMalformedJson() {
        Environment environment = environment("READY");
        environment.setPortMappings("[1,2]");
        environment.setResourceUsage("{\"cpu\":\"high\"}");
        when(environmentMapper.selectById(1L)).thenReturn(environment);

        EnvironmentDTO dto = environmentService.getEnvironment(1L);

        assertThat(dto.getStatus()).isEqualTo("READY");
        assertThat(dto.getPortMappings()).isNull();
        assertThat(dto.getResourceUsage()).containsEntry("cpu", null).hasSize(1);

        environment.setPortMappings("null");
        environment.setResourceUsage("   ");
        dto = environmentService.getEnvironment(1L);
        assertThat(dto.getPortMappings()).isNull();
        assertThat(dto.getResourceUsage()).isNull();

        environment.setPortMappings("{\"mysql\":33061.5}");
        environment.setResourceUsage("{\"cpu\":0.5}");
        dto = environmentService.getEnvironment(1L);
        assertThat(dto.getPortMappings()).isNull();
        assertThat(dto.getResourceUsage()).containsEntry("cpu", 0.5d);
    }

    @Test
    @DisplayName("资源用量保留 sampledAt，非法 cpu 只置空 cpu")
    public void shouldKeepSampledAtAndNullOnlyBadCpu() {
        Environment environment = environment("READY");
        environment.setResourceUsage(
                "{\"cpu\":0.25,\"memory\":128,\"sampledAt\":\"2026-10-09T01:00:00\"}");
        when(environmentMapper.selectById(1L)).thenReturn(environment);

        EnvironmentDTO dto = environmentService.getEnvironment(1L);

        assertThat(dto.getResourceUsage())
                .containsEntry("cpu", 0.25d)
                .containsEntry("memory", 128)
                .containsEntry("sampledAt", "2026-10-09T01:00:00");

        environment.setResourceUsage(
                "{\"cpu\":\"high\",\"memory\":128,\"sampledAt\":\"2026-10-09T01:00:00\",\"note\":\"skip\"}");
        dto = environmentService.getEnvironment(1L);

        assertThat(dto.getResourceUsage())
                .containsEntry("cpu", null)
                .containsEntry("memory", 128)
                .containsEntry("sampledAt", "2026-10-09T01:00:00")
                .doesNotContainKey("note");
        assertThat(dto.getPortMappings()).containsEntry("mysql", 33061);
    }

    @Test
    @DisplayName("环境不存在时返回 404")
    public void shouldThrowWhenEnvironmentMissing() {
        when(environmentMapper.selectById(8L)).thenReturn(null);

        assertThatThrownBy(() -> environmentService.getEnvironment(8L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.ENVIRONMENT_NOT_FOUND.getCode());
        assertThatThrownBy(() -> environmentService.releaseEnvironment(8L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.ENVIRONMENT_NOT_FOUND.getCode());

        verify(environmentMapper, never()).updateById(any(Environment.class));
    }

    @Test
    @DisplayName("释放环境时停止容器并置为 IDLE")
    public void shouldReleaseEnvironment() {
        Environment environment = environment("READY");
        when(environmentMapper.selectById(1L)).thenReturn(environment);
        Container mysql = container(11L, "cid-mysql");
        Container blank = container(12L, " ");
        when(containerMapper.findByEnvironmentId(1L)).thenReturn(List.of(mysql, blank));

        environmentService.releaseEnvironment(1L);

        verify(dockerService).assertDockerAvailable();
        verify(dockerService).stopAndRemoveContainer("cid-mysql");
        verify(dockerService, never()).stopAndRemoveContainer(" ");
        assertThat(mysql.getStatus()).isEqualTo("STOPPED");
        assertThat(mysql.getStoppedAt()).isNotNull();
        assertThat(blank.getStatus()).isEqualTo("STOPPED");
        assertThat(blank.getStoppedAt()).isNotNull();
        verify(containerMapper).updateById(mysql);
        verify(containerMapper).updateById(blank);
        assertThat(statusUpdates).containsExactly("RELEASING", "IDLE");
    }

    @Test
    @DisplayName("单个容器失败时记为 ERROR，环境置 ERROR 并返回 Docker 错误")
    public void shouldMarkErrorWhenOneContainerFails() {
        Environment environment = environment("BUSY");
        when(environmentMapper.selectById(1L)).thenReturn(environment);
        Container failed = container(11L, "cid-fail");
        Container ok = container(12L, "cid-ok");
        when(containerMapper.findByEnvironmentId(1L)).thenReturn(List.of(failed, ok));
        doThrow(new BusinessException(ErrorCode.DOCKER_ERROR, "删除容器失败: tcp://docker-host:2375"))
                .when(dockerService).stopAndRemoveContainer("cid-fail");

        assertThatThrownBy(() -> environmentService.releaseEnvironment(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.DOCKER_ERROR.getCode())
                .hasMessage("1 个容器释放失败");

        verify(dockerService).stopAndRemoveContainer("cid-ok");
        assertThat(failed.getStatus()).isEqualTo("ERROR");
        assertThat(failed.getStoppedAt()).isNull();
        assertThat(ok.getStatus()).isEqualTo("STOPPED");
        assertThat(ok.getStoppedAt()).isNotNull();
        verify(containerMapper).updateById(failed);
        verify(containerMapper).updateById(ok);
        assertThat(statusUpdates).containsExactly("RELEASING", "ERROR");
    }

    @Test
    @DisplayName("ERROR 环境可再次释放，全部成功后变为 IDLE")
    public void shouldRetryReleaseFromError() {
        Environment environment = environment("ERROR");
        when(environmentMapper.selectById(1L)).thenReturn(environment);
        Container previouslyFailed = container(11L, "cid-fail");
        previouslyFailed.setStatus("ERROR");
        Container alreadyStopped = container(12L, "cid-ok");
        alreadyStopped.setStatus("STOPPED");
        when(containerMapper.findByEnvironmentId(1L)).thenReturn(List.of(previouslyFailed, alreadyStopped));

        environmentService.releaseEnvironment(1L);

        verify(dockerService).stopAndRemoveContainer("cid-fail");
        verify(dockerService).stopAndRemoveContainer("cid-ok");
        assertThat(previouslyFailed.getStatus()).isEqualTo("STOPPED");
        assertThat(previouslyFailed.getStoppedAt()).isNotNull();
        assertThat(alreadyStopped.getStatus()).isEqualTo("STOPPED");
        assertThat(statusUpdates).containsExactly("RELEASING", "IDLE");
    }

    @Test
    @DisplayName("Docker 不可用时标记 ERROR 且不改容器")
    public void shouldMarkErrorWhenDockerUnavailable() {
        Environment environment = environment("READY");
        when(environmentMapper.selectById(1L)).thenReturn(environment);
        Container mysql = container(11L, "cid-mysql");
        when(containerMapper.findByEnvironmentId(1L)).thenReturn(List.of(mysql));
        doThrow(new BusinessException(ErrorCode.DOCKER_ERROR, "Docker 服务不可用"))
                .when(dockerService).assertDockerAvailable();

        assertThatThrownBy(() -> environmentService.releaseEnvironment(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.DOCKER_ERROR.getCode())
                .hasMessage("Docker 服务不可用");

        verify(dockerService, never()).stopAndRemoveContainer(any());
        verify(containerMapper, never()).updateById(any(Container.class));
        assertThat(mysql.getStatus()).isEqualTo("RUNNING");
        assertThat(statusUpdates).containsExactly("RELEASING", "ERROR");
    }

    @Test
    @DisplayName("RETAINED 可释放，成功后变为 IDLE")
    public void shouldReleaseRetainedEnvironment() {
        Environment environment = environment("RETAINED");
        when(environmentMapper.selectById(1L)).thenReturn(environment);
        Container mysql = container(11L, "cid-mysql");
        when(containerMapper.findByEnvironmentId(1L)).thenReturn(List.of(mysql));

        environmentService.releaseEnvironment(1L);

        verify(dockerService).assertDockerAvailable();
        verify(dockerService).stopAndRemoveContainer("cid-mysql");
        assertThat(mysql.getStatus()).isEqualTo("STOPPED");
        assertThat(mysql.getStoppedAt()).isNotNull();
        assertThat(statusUpdates).containsExactly("RELEASING", "IDLE");
    }

    @Test
    @DisplayName("IDLE 或 RELEASING 时拒绝再次释放")
    public void shouldRejectReleaseWhenIdleOrReleasing() {
        when(environmentMapper.selectById(1L)).thenReturn(environment("IDLE"));

        assertThatThrownBy(() -> environmentService.releaseEnvironment(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OPERATION_NOT_ALLOWED.getCode())
                .hasMessage("环境已空闲或正在释放");

        when(environmentMapper.selectById(1L)).thenReturn(environment("RELEASING"));
        assertThatThrownBy(() -> environmentService.releaseEnvironment(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OPERATION_NOT_ALLOWED.getCode());

        verify(environmentMapper, never()).updateById(any(Environment.class));
        verify(dockerService, never()).assertDockerAvailable();
    }

    @Test
    @DisplayName("没有容器时不访问 Docker，ERROR 状态允许释放")
    public void shouldReleaseWithoutDockerWhenNoContainers() {
        Environment environment = environment("ERROR");
        when(environmentMapper.selectById(1L)).thenReturn(environment);
        when(containerMapper.findByEnvironmentId(1L)).thenReturn(List.of());

        environmentService.releaseEnvironment(1L);

        verify(dockerService, never()).assertDockerAvailable();
        verify(dockerService, never()).stopAndRemoveContainer(any());
        assertThat(statusUpdates).containsExactly("RELEASING", "IDLE");
    }

    private Environment environment(String status) {
        Environment environment = new Environment();
        environment.setId(1L);
        environment.setEnvCode("env-abc123");
        environment.setStatus(status);
        environment.setTaskId(9L);
        environment.setComposeContent("services: {}\n");
        environment.setAccessUrl("http://127.0.0.1:18080");
        environment.setPortMappings("{\"mysql\":33061,\"redis\":63791}");
        environment.setResourceUsage("{\"cpu\":0.5,\"memory\":512}");
        environment.setExpiresAt(LocalDateTime.of(2026, 10, 9, 12, 0));
        environment.setCreatedAt(LocalDateTime.of(2026, 10, 9, 8, 0));
        return environment;
    }

    private Container container(Long id, String containerId) {
        Container container = new Container();
        container.setId(id);
        container.setEnvironmentId(1L);
        container.setContainerId(containerId);
        container.setStatus("RUNNING");
        return container;
    }
}
