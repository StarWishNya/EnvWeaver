package com.sentinel.lifecycle;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.LifecycleProperties;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Task;
import com.sentinel.enums.ErrorCode;
import com.sentinel.event.TaskCreatedEvent;
import com.sentinel.event.TaskStatusChangedEvent;
import com.sentinel.mapper.EnvironmentMapper;
import com.sentinel.service.DiagnosisService;
import com.sentinel.service.EnvironmentProvisioner;
import com.sentinel.service.EnvironmentService;
import com.sentinel.service.TaskService;
import com.sentinel.service.provision.ProvisioningFailedException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 任务生命周期编排单元测试。不启动 Docker，也不访问网络。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("任务生命周期编排")
class TaskLifecycleOrchestratorTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-09T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock
    private TaskService taskService;

    @Mock
    private EnvironmentService environmentService;

    @Mock
    private DiagnosisService diagnosisService;

    @Mock
    private EnvironmentProvisioner environmentProvisioner;

    @Mock
    private EnvironmentMapper environmentMapper;

    private LifecycleProperties properties;
    private ProvisioningProperties provisioningProperties;
    private TaskLifecycleOrchestrator orchestrator;

    @BeforeAll
    static void initLambdaCache() {
        MybatisLambdaCaches.install(Environment.class);
    }

    @BeforeEach
    void setUp() {
        properties = new LifecycleProperties();
        provisioningProperties = new ProvisioningProperties();
        provisioningProperties.setTtl(Duration.ofMinutes(45));
        orchestrator = orchestrator(Runnable::run);
    }

    @Test
    @DisplayName("创建事件供给环境并进入 RUNNING")
    void shouldProvisionAndMoveTaskToRunning() {
        Task task = queuedTask(1L);
        Environment environment = environment(9L, "READY");
        when(taskService.transitionStatus(1L, "PENDING", "QUEUED")).thenReturn(true);
        when(taskService.transitionStatus(1L, "QUEUED", "RUNNING")).thenReturn(true);
        when(taskService.getTaskById(1L)).thenReturn(task);
        when(environmentProvisioner.provision(task)).thenReturn(environment);
        when(environmentMapper.update(isNull(), any())).thenReturn(1);

        orchestrator.onTaskCreated(new TaskCreatedEvent(1L));

        verify(environmentProvisioner).provision(task);
        verify(taskService).attachEnvironment(1L, 9L);
        ArgumentCaptor<Wrapper<Environment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(environmentMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(LambdaUpdateWrapper.class);
        assertThat(MybatisLambdaCaches.values(captor.getValue()))
                .contains("BUSY", NOW.plus(provisioningProperties.getTtl()), 9L);
        verify(environmentService, never()).releaseEnvironment(any());
    }

    @Test
    @DisplayName("供给器已写入过期时间时标记占用不覆盖")
    void shouldKeepProvisionerExpiresAtWhenMarkingBusy() {
        Task task = queuedTask(1L);
        Environment environment = environment(9L, "READY");
        LocalDateTime provisionerExpiry = NOW.plusHours(5);
        environment.setExpiresAt(provisionerExpiry);
        when(taskService.transitionStatus(1L, "PENDING", "QUEUED")).thenReturn(true);
        when(taskService.transitionStatus(1L, "QUEUED", "RUNNING")).thenReturn(true);
        when(taskService.getTaskById(1L)).thenReturn(task);
        when(environmentProvisioner.provision(task)).thenReturn(environment);
        when(environmentMapper.update(isNull(), any())).thenReturn(1);

        orchestrator.onTaskCreated(new TaskCreatedEvent(1L));

        ArgumentCaptor<Wrapper<Environment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(environmentMapper).update(isNull(), captor.capture());
        assertThat(MybatisLambdaCaches.values(captor.getValue()))
                .containsExactlyInAnyOrder("BUSY", 9L);
        verify(taskService).transitionStatus(1L, "QUEUED", "RUNNING");
    }

    @Test
    @DisplayName("比较并设置失败时不供给")
    void shouldSkipProvisionWhenCompareAndSetLost() {
        when(taskService.transitionStatus(1L, "PENDING", "QUEUED")).thenReturn(false);

        orchestrator.onTaskCreated(new TaskCreatedEvent(1L));

        verifyNoInteractions(environmentProvisioner);
        verify(taskService, never()).attachEnvironment(any(), any());
        verify(taskService, never()).setTaskError(any(), any());
        verify(environmentService, never()).releaseEnvironment(any());
    }

    @Test
    @DisplayName("供给失败时把任务标为失败")
    void shouldSetTaskErrorWhenProvisionFails() {
        Task task = queuedTask(1L);
        when(taskService.transitionStatus(1L, "PENDING", "QUEUED")).thenReturn(true);
        when(taskService.getTaskById(1L)).thenReturn(task);
        when(environmentProvisioner.provision(task))
                .thenThrow(new BusinessException(ErrorCode.DOCKER_ERROR, "端口不足"));

        orchestrator.onTaskCreated(new TaskCreatedEvent(1L));

        verify(taskService).setTaskError(1L, "端口不足");
        verify(taskService, never()).attachEnvironment(any(), any());
        verify(environmentService, never()).releaseEnvironment(any());
    }

    @Test
    @DisplayName("供给失败且带环境编号时先绑定再标失败，不释放")
    void shouldAttachEnvironmentWhenProvisioningFailsWithEnvironmentId() {
        Task task = queuedTask(1L);
        when(taskService.transitionStatus(1L, "PENDING", "QUEUED")).thenReturn(true);
        when(taskService.getTaskById(1L)).thenReturn(task);
        when(environmentProvisioner.provision(task))
                .thenThrow(new ProvisioningFailedException(
                        ErrorCode.ENVIRONMENT_CREATE_FAILED, "镜像拉取超时: connection reset", 9L));

        orchestrator.onTaskCreated(new TaskCreatedEvent(1L));

        var order = inOrder(taskService);
        order.verify(taskService).attachEnvironment(1L, 9L);
        order.verify(taskService).setTaskError(1L, "镜像拉取超时: connection reset");
        verify(environmentService, never()).releaseEnvironment(any());
        verify(environmentMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("供给失败没有环境编号时不绑定")
    void shouldNotAttachWhenProvisioningFailsWithoutEnvironmentId() {
        Task task = queuedTask(1L);
        when(taskService.transitionStatus(1L, "PENDING", "QUEUED")).thenReturn(true);
        when(taskService.getTaskById(1L)).thenReturn(task);
        when(environmentProvisioner.provision(task))
                .thenThrow(new ProvisioningFailedException(ErrorCode.DOCKER_ERROR, "端口不足", null));

        orchestrator.onTaskCreated(new TaskCreatedEvent(1L));

        verify(taskService).setTaskError(1L, "端口不足");
        verify(taskService, never()).attachEnvironment(any(), any());
        verify(environmentService, never()).releaseEnvironment(any());
    }

    @Test
    @DisplayName("绑定失败环境抛错时仍标失败且不释放")
    void shouldStillFailTaskWhenAttachAfterProvisionFailureThrows() {
        Task task = queuedTask(1L);
        when(taskService.transitionStatus(1L, "PENDING", "QUEUED")).thenReturn(true);
        when(taskService.getTaskById(1L)).thenReturn(task);
        when(environmentProvisioner.provision(task))
                .thenThrow(new ProvisioningFailedException(
                        ErrorCode.ENVIRONMENT_CREATE_FAILED, "容器未就绪", 9L));
        doThrow(new BusinessException(ErrorCode.TASK_NOT_FOUND))
                .when(taskService).attachEnvironment(1L, 9L);

        assertThatCode(() -> orchestrator.onTaskCreated(new TaskCreatedEvent(1L)))
                .doesNotThrowAnyException();

        verify(taskService).setTaskError(1L, "容器未就绪");
        verify(environmentService, never()).releaseEnvironment(any());
    }

    @Test
    @DisplayName("供给失败后的 FAILED 事件只保留现场，不立刻释放")
    void shouldRetainEnvironmentAfterProvisioningFailureInsteadOfReleasing() {
        Task queued = queuedTask(1L);
        Task failed = queuedTask(1L);
        failed.setStatus("FAILED");
        failed.setEnvironmentId(9L);
        when(taskService.transitionStatus(1L, "PENDING", "QUEUED")).thenReturn(true);
        when(taskService.getTaskById(1L)).thenReturn(queued, failed);
        when(environmentProvisioner.provision(queued))
                .thenThrow(new ProvisioningFailedException(
                        ErrorCode.ENVIRONMENT_CREATE_FAILED, "容器未就绪", 9L));
        when(environmentMapper.update(isNull(), any())).thenReturn(1);

        orchestrator.onTaskCreated(new TaskCreatedEvent(1L));
        orchestrator.onTaskStatusChanged(new TaskStatusChangedEvent(1L, "QUEUED", "FAILED"));

        var order = inOrder(taskService, diagnosisService, environmentMapper);
        order.verify(taskService).attachEnvironment(1L, 9L);
        order.verify(taskService).setTaskError(1L, "容器未就绪");
        order.verify(diagnosisService).requestDiagnosis(1L, null);
        ArgumentCaptor<Wrapper<Environment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        order.verify(environmentMapper).update(isNull(), captor.capture());
        assertThat(MybatisLambdaCaches.values(captor.getValue()))
                .contains(NOW.plus(properties.getFailedRetention()), 9L);
        verify(environmentService, never()).releaseEnvironment(any());
    }

    @Test
    @DisplayName("供给期间被取消则释放新环境")
    void shouldReleaseEnvironmentWhenCancelledDuringProvision() {
        Task queued = queuedTask(1L);
        Task cancelled = queuedTask(1L);
        cancelled.setStatus("CANCELLED");
        Environment environment = environment(9L, "READY");
        when(taskService.transitionStatus(1L, "PENDING", "QUEUED")).thenReturn(true);
        when(taskService.getTaskById(1L)).thenReturn(queued, cancelled);
        when(environmentProvisioner.provision(queued)).thenReturn(environment);

        orchestrator.onTaskCreated(new TaskCreatedEvent(1L));

        verify(environmentService).releaseEnvironment(9L);
        verify(taskService, never()).attachEnvironment(any(), any());
        verify(taskService, never()).transitionStatus(1L, "QUEUED", "RUNNING");
    }

    @Test
    @DisplayName("进入 RUNNING 的比较并设置失败时释放环境")
    void shouldReleaseEnvironmentWhenRunningTransitionLost() {
        Task task = queuedTask(1L);
        Environment environment = environment(9L, "READY");
        when(taskService.transitionStatus(1L, "PENDING", "QUEUED")).thenReturn(true);
        when(taskService.transitionStatus(1L, "QUEUED", "RUNNING")).thenReturn(false);
        when(taskService.getTaskById(1L)).thenReturn(task);
        when(environmentProvisioner.provision(task)).thenReturn(environment);
        when(environmentMapper.update(isNull(), any())).thenReturn(1);

        orchestrator.onTaskCreated(new TaskCreatedEvent(1L));

        verify(taskService).attachEnvironment(1L, 9L);
        verify(environmentService).releaseEnvironment(9L);
    }

    @Test
    @DisplayName("成功或取消且已关联环境时释放")
    void shouldReleaseEnvironmentOnSuccessOrCancelled() {
        Task task = queuedTask(4L);
        task.setStatus("SUCCESS");
        task.setEnvironmentId(8L);
        when(taskService.getTaskById(4L)).thenReturn(task);

        orchestrator.onTaskStatusChanged(new TaskStatusChangedEvent(4L, "RUNNING", "SUCCESS"));
        orchestrator.onTaskStatusChanged(new TaskStatusChangedEvent(4L, "QUEUED", "CANCELLED"));

        verify(environmentService, times(2)).releaseEnvironment(8L);
        verifyNoInteractions(diagnosisService);
    }

    @Test
    @DisplayName("释放抛出业务异常时不向外抛")
    void shouldKeepGoingWhenReleaseThrows() {
        Task task = queuedTask(4L);
        task.setEnvironmentId(8L);
        when(taskService.getTaskById(4L)).thenReturn(task);
        doThrow(new BusinessException(ErrorCode.DOCKER_ERROR, "1 个容器释放失败"))
                .when(environmentService).releaseEnvironment(8L);

        assertThatCode(() -> orchestrator.onTaskStatusChanged(
                new TaskStatusChangedEvent(4L, "RUNNING", "SUCCESS")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("失败时先诊断再设置保留时间，且不释放")
    void shouldRequestDiagnosisBeforeRetentionAndNotRelease() {
        Task task = queuedTask(5L);
        task.setStatus("FAILED");
        task.setEnvironmentId(12L);
        when(taskService.getTaskById(5L)).thenReturn(task);
        when(environmentMapper.update(isNull(), any())).thenReturn(1);

        orchestrator.onTaskStatusChanged(new TaskStatusChangedEvent(5L, "RUNNING", "FAILED"));

        var order = inOrder(diagnosisService, environmentMapper);
        order.verify(diagnosisService).requestDiagnosis(5L, null);
        ArgumentCaptor<Wrapper<Environment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        order.verify(environmentMapper).update(isNull(), captor.capture());
        assertThat(MybatisLambdaCaches.values(captor.getValue()))
                .contains(NOW.plus(properties.getFailedRetention()), 12L);
        assertThat(MybatisLambdaCaches.values(captor.getValue())).doesNotContain("BUSY");
        verify(environmentService, never()).releaseEnvironment(any());
    }

    @Test
    @DisplayName("诊断失败被吞掉，仍然设置保留时间")
    void shouldSwallowDiagnosisFailureAndStillSetRetention() {
        Task task = queuedTask(5L);
        task.setStatus("FAILED");
        task.setEnvironmentId(12L);
        when(taskService.getTaskById(5L)).thenReturn(task);
        when(diagnosisService.requestDiagnosis(5L, null))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "没有可诊断的内容"));
        when(environmentMapper.update(isNull(), any())).thenReturn(1);

        assertThatCode(() -> orchestrator.onTaskStatusChanged(
                new TaskStatusChangedEvent(5L, "RUNNING", "FAILED")))
                .doesNotThrowAnyException();

        verify(environmentMapper).update(isNull(), any());
        verify(environmentService, never()).releaseEnvironment(any());
    }

    @Test
    @DisplayName("开关关闭时不供给、不诊断、不释放")
    void shouldDoNothingWhenFlagsDisabled() {
        properties.setAutoProvision(false);
        properties.setAutoDiagnose(false);
        Task failed = queuedTask(6L);
        failed.setStatus("FAILED");
        failed.setEnvironmentId(3L);
        when(taskService.getTaskById(6L)).thenReturn(failed);

        orchestrator.onTaskCreated(new TaskCreatedEvent(6L));
        orchestrator.sweepPending();
        orchestrator.onTaskStatusChanged(new TaskStatusChangedEvent(6L, "RUNNING", "FAILED"));

        verifyNoInteractions(environmentProvisioner, diagnosisService);
        verify(taskService, never()).transitionStatus(any(), any(), any());
        verify(taskService, never()).listTasksByStatus(any());
        verify(environmentService, never()).releaseEnvironment(any());
        verify(environmentMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("补扫只提交创建超过 30 秒的 PENDING")
    void shouldSweepOldPendingTasksOnly() {
        Task young = queuedTask(1L);
        young.setStatus("PENDING");
        young.setCreatedAt(NOW.minusSeconds(10));
        Task old = queuedTask(2L);
        old.setStatus("PENDING");
        old.setCreatedAt(NOW.minus(TaskLifecycleOrchestrator.PENDING_MIN_AGE).minusSeconds(1));
        Task persisted = queuedTask(2L);
        persisted.setCreatedAt(old.getCreatedAt());
        Environment environment = environment(9L, "READY");
        when(taskService.listTasksByStatus("PENDING")).thenReturn(List.of(young, old));
        when(taskService.transitionStatus(2L, "PENDING", "QUEUED")).thenReturn(true);
        when(taskService.transitionStatus(2L, "QUEUED", "RUNNING")).thenReturn(true);
        when(taskService.getTaskById(2L)).thenReturn(persisted);
        when(environmentProvisioner.provision(persisted)).thenReturn(environment);

        orchestrator.sweepPending();

        verify(environmentProvisioner).provision(persisted);
        verify(taskService, never()).transitionStatus(eq(1L), any(), any());
    }

    @Test
    @DisplayName("补扫每轮最多 20 条")
    void shouldLimitPendingSweepToTwenty() {
        List<Task> tasks = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            Task task = queuedTask((long) i);
            task.setStatus("PENDING");
            task.setCreatedAt(NOW.minusMinutes(5));
            tasks.add(task);
        }
        when(taskService.listTasksByStatus("PENDING")).thenReturn(tasks);
        when(taskService.transitionStatus(any(), eq("PENDING"), eq("QUEUED"))).thenReturn(true);
        when(taskService.getTaskById(any())).thenAnswer(invocation -> {
            Task task = queuedTask(invocation.getArgument(0));
            return task;
        });
        when(environmentProvisioner.provision(any())).thenAnswer(invocation -> environment(100L, "READY"));
        when(taskService.transitionStatus(any(), eq("QUEUED"), eq("RUNNING"))).thenReturn(true);

        orchestrator.sweepPending();

        verify(environmentProvisioner, times(TaskLifecycleOrchestrator.PENDING_BATCH_LIMIT)).provision(any());
    }

    @Test
    @DisplayName("线程池拒绝时补扫不抛错，留给下一轮")
    void shouldLeaveTaskWhenExecutorRejects() {
        Task old = queuedTask(2L);
        old.setStatus("PENDING");
        old.setCreatedAt(NOW.minusMinutes(5));
        when(taskService.listTasksByStatus("PENDING")).thenReturn(List.of(old));
        orchestrator = orchestrator(command -> {
            throw new RejectedExecutionException("full");
        });

        assertThatCode(() -> orchestrator.sweepPending()).doesNotThrowAnyException();

        verifyNoInteractions(environmentProvisioner);
    }

    private TaskLifecycleOrchestrator orchestrator(Executor executor) {
        return new TaskLifecycleOrchestrator(taskService, environmentService, diagnosisService,
                environmentProvisioner, environmentMapper, properties, provisioningProperties, executor, CLOCK);
    }

    private static Task queuedTask(Long id) {
        Task task = new Task();
        task.setId(id);
        task.setStatus("QUEUED");
        task.setCreatedAt(NOW.minusMinutes(1));
        return task;
    }

    private static Environment environment(Long id, String status) {
        Environment environment = new Environment();
        environment.setId(id);
        environment.setStatus(status);
        return environment;
    }

}
