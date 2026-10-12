package com.sentinel.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sentinel.config.BusinessException;
import com.sentinel.domain.Container;
import com.sentinel.domain.Task;
import com.sentinel.dto.CreateTaskRequest;
import com.sentinel.dto.TaskLogsResponse;
import com.sentinel.dto.TaskStatusResponse;
import com.sentinel.enums.ErrorCode;
import com.sentinel.event.TaskCreatedEvent;
import com.sentinel.event.TaskStatusChangedEvent;
import com.sentinel.mapper.TaskMapper;
import com.sentinel.service.provision.DependencyCatalog;
import com.sentinel.service.provision.TemplateCatalog;
import com.sentinel.testutil.CreateTaskRequestBuilder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import com.sentinel.testutil.TaskBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TaskService 单元测试
 * 使用 Mockito 模拟依赖
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskService 单元测试")
public class TaskServiceTest {

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private DockerService dockerService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TemplateCatalog templateCatalog;

    @Mock
    private DependencyCatalog dependencyCatalog;

    @InjectMocks
    private TaskService taskService;

    private Task testTask;
    private CreateTaskRequest testRequest;

    @BeforeEach
    public void setUp() {
        testTask = TaskBuilder.aTask()
            .withId(1L)
            .withTaskCode("TEST-001")
            .withServiceId("user-service")
            .pending()
            .build();

        testRequest = CreateTaskRequestBuilder.aCreateTaskRequest()
            .withTaskCode("TEST-001")
            .withServiceId("user-service")
            .unitTest()
            .build();
    }

    @Test
    @DisplayName("测试创建任务成功")
    public void shouldCreateTask_whenValidRequest() {
        // Given
        when(taskMapper.insert(any(Task.class))).thenAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.setId(1L);
            return 1;
        });

        // When
        Task result = taskService.createTask(testRequest);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getTaskCode()).isEqualTo("TEST-001");
        assertThat(result.getServiceId()).isEqualTo("user-service");
        assertThat(result.getStatus()).isEqualTo("PENDING");
        
        verify(taskMapper, times(1)).insert(any(Task.class));
        verify(eventPublisher).publishEvent(new TaskCreatedEvent(1L));
    }

    @Test
    @DisplayName("测试创建任务时设置默认状态为PENDING")
    public void shouldSetDefaultStatus_whenCreateTask() {
        // Given
        when(taskMapper.insert(any(Task.class))).thenReturn(1);

        // When
        Task result = taskService.createTask(testRequest);

        // Then
        assertThat(result.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("测试根据ID获取任务成功")
    public void shouldGetTask_whenValidId() {
        // Given
        when(taskMapper.selectById(1L)).thenReturn(testTask);

        // When
        Task result = taskService.getTaskById(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getTaskCode()).isEqualTo("TEST-001");
        
        verify(taskMapper, times(1)).selectById(1L);
    }

    @Test
    @DisplayName("测试根据ID获取任务失败 - 任务不存在")
    public void shouldThrowException_whenTaskNotFound() {
        // Given
        when(taskMapper.selectById(999L)).thenReturn(null);

        // When & Then
        assertThatThrownBy(() -> taskService.getTaskById(999L))
            .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.TASK_NOT_FOUND.getCode());
        
        verify(taskMapper, times(1)).selectById(999L);
    }

    @Test
    @DisplayName("测试分页查询任务列表")
    public void shouldListTasks_whenValidPagination() {
        // Given
        Task task1 = TaskBuilder.aTask().withId(1L).pending().build();
        Task task2 = TaskBuilder.aTask().withId(2L).running().build();
        
        Page<Task> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(task1, task2));
        page.setTotal(2);

        when(taskMapper.selectTaskPage(any(Page.class), isNull(), isNull())).thenReturn(page);

        // When
        IPage<Task> result = taskService.listTasks(0, 10, null, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getTotal()).isEqualTo(2);

        ArgumentCaptor<Page<Task>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(taskMapper).selectTaskPage(pageCaptor.capture(), isNull(), isNull());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("测试按状态过滤查询任务列表")
    public void shouldListTasks_whenFilterByStatus() {
        // Given
        Task task1 = TaskBuilder.aTask().withId(1L).running().build();
        Task task2 = TaskBuilder.aTask().withId(2L).running().build();
        
        Page<Task> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(task1, task2));
        page.setTotal(2);

        when(taskMapper.selectTaskPage(any(Page.class), eq("RUNNING"), eq("UNIT_TEST"))).thenReturn(page);

        // When
        IPage<Task> result = taskService.listTasks(1, 10, "RUNNING", "UNIT_TEST");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getRecords()).allMatch(task -> "RUNNING".equals(task.getStatus()));

        ArgumentCaptor<Page<Task>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(taskMapper).selectTaskPage(pageCaptor.capture(), eq("RUNNING"), eq("UNIT_TEST"));
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
    }

    @Test
    @DisplayName("测试根据状态查询任务列表")
    public void shouldListTasksByStatus_whenValidStatus() {
        // Given
        Task task1 = TaskBuilder.aTask().withId(1L).success().build();
        Task task2 = TaskBuilder.aTask().withId(2L).success().build();
        List<Task> tasks = Arrays.asList(task1, task2);

        when(taskMapper.findByStatus("SUCCESS")).thenReturn(tasks);

        // When
        List<Task> result = taskService.listTasksByStatus("SUCCESS");

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(task -> "SUCCESS".equals(task.getStatus()));
        
        verify(taskMapper, times(1)).findByStatus("SUCCESS");
    }

    @Test
    @DisplayName("测试更新任务状态为RUNNING")
    public void shouldUpdateTaskStatus_whenStatusIsRunning() {
        // Given
        when(taskMapper.selectById(1L)).thenReturn(testTask);
        when(taskMapper.updateById(any(Task.class))).thenReturn(1);

        // When
        taskService.updateTaskStatus(1L, "RUNNING");

        // Then
        verify(taskMapper, times(1)).selectById(1L);
        verify(taskMapper, times(1)).updateById(argThat((Task task) -> 
            "RUNNING".equals(task.getStatus()) && task.getStartedAt() != null
        ));
        verify(eventPublisher).publishEvent(new TaskStatusChangedEvent(1L, "PENDING", "RUNNING"));
    }

    @Test
    @DisplayName("测试更新任务状态为SUCCESS")
    public void shouldUpdateTaskStatus_whenStatusIsSuccess() {
        // Given
        when(taskMapper.selectById(1L)).thenReturn(testTask);
        when(taskMapper.updateById(any(Task.class))).thenReturn(1);

        // When
        taskService.updateTaskStatus(1L, "SUCCESS");

        // Then
        verify(taskMapper, times(1)).updateById(argThat((Task task) -> 
            "SUCCESS".equals(task.getStatus()) && task.getCompletedAt() != null
        ));
        verify(eventPublisher).publishEvent(new TaskStatusChangedEvent(1L, "PENDING", "SUCCESS"));
    }

    @Test
    @DisplayName("测试更新任务状态为CANCELLED")
    public void shouldUpdateTaskStatus_whenStatusIsCancelled() {
        // Given
        when(taskMapper.selectById(1L)).thenReturn(testTask);
        when(taskMapper.updateById(any(Task.class))).thenReturn(1);

        // When
        taskService.updateTaskStatus(1L, "CANCELLED");

        // Then
        verify(taskMapper, times(1)).updateById(argThat((Task task) -> 
            "CANCELLED".equals(task.getStatus()) && task.getCompletedAt() != null
        ));
        verify(eventPublisher).publishEvent(new TaskStatusChangedEvent(1L, "PENDING", "CANCELLED"));
    }

    @Test
    @DisplayName("测试取消PENDING状态的任务")
    public void shouldCancelTask_whenTaskIsPending() {
        // Given
        when(taskMapper.selectById(1L)).thenReturn(testTask);
        when(taskMapper.updateById(any(Task.class))).thenReturn(1);

        // When
        taskService.cancelTask(1L);

        // Then
        verify(taskMapper, times(2)).selectById(1L);  // cancelTask 和 updateTaskStatus 各调用一次
        verify(taskMapper, times(1)).updateById(argThat((Task task) -> 
            "CANCELLED".equals(task.getStatus()) && task.getCompletedAt() != null
        ));
        verify(eventPublisher).publishEvent(new TaskStatusChangedEvent(1L, "PENDING", "CANCELLED"));
    }

    @Test
    @DisplayName("测试取消RUNNING状态的任务")
    public void shouldCancelTask_whenTaskIsRunning() {
        // Given
        Task runningTask = TaskBuilder.aTask().withId(1L).running().build();
        when(taskMapper.selectById(1L)).thenReturn(runningTask);
        when(taskMapper.updateById(any(Task.class))).thenReturn(1);

        // When
        taskService.cancelTask(1L);

        // Then
        verify(taskMapper, times(1)).updateById(argThat((Task task) -> 
            "CANCELLED".equals(task.getStatus())
        ));
        verify(eventPublisher, times(1)).publishEvent(new TaskStatusChangedEvent(1L, "RUNNING", "CANCELLED"));
    }

    @Test
    @DisplayName("测试取消已完成的任务失败 - SUCCESS状态")
    public void shouldThrowException_whenCancelSuccessTask() {
        // Given
        Task successTask = TaskBuilder.aTask().withId(1L).success().build();
        when(taskMapper.selectById(1L)).thenReturn(successTask);

        // When & Then
        assertThatThrownBy(() -> taskService.cancelTask(1L))
            .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OPERATION_NOT_ALLOWED.getCode());
        
        verify(taskMapper, never()).updateById(any(Task.class));
    }

    @Test
    @DisplayName("测试取消已失败的任务失败 - FAILED状态")
    public void shouldThrowException_whenCancelFailedTask() {
        // Given
        Task failedTask = TaskBuilder.aTask().withId(1L).failed().build();
        when(taskMapper.selectById(1L)).thenReturn(failedTask);

        // When & Then
        assertThatThrownBy(() -> taskService.cancelTask(1L))
            .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OPERATION_NOT_ALLOWED.getCode());
        
        verify(taskMapper, never()).updateById(any(Task.class));
    }

    @Test
    @DisplayName("测试取消已取消的任务失败 - CANCELLED状态")
    public void shouldThrowException_whenCancelCancelledTask() {
        // Given
        Task cancelledTask = TaskBuilder.aTask().withId(1L).cancelled().build();
        when(taskMapper.selectById(1L)).thenReturn(cancelledTask);

        // When & Then
        assertThatThrownBy(() -> taskService.cancelTask(1L))
            .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OPERATION_NOT_ALLOWED.getCode());
        
        verify(taskMapper, never()).updateById(any(Task.class));
    }

    @Test
    @DisplayName("测试设置任务错误信息")
    public void shouldSetTaskError_whenTaskFails() {
        // Given
        String errorMessage = "Database connection failed";
        when(taskMapper.selectById(1L)).thenReturn(testTask);
        when(taskMapper.updateById(any(Task.class))).thenReturn(1);

        // When
        taskService.setTaskError(1L, errorMessage);

        // Then
        verify(taskMapper, times(1)).updateById(argThat((Task task) -> 
            "FAILED".equals(task.getStatus()) &&
            errorMessage.equals(task.getErrorMessage()) &&
            task.getCompletedAt() != null
        ));
        verify(eventPublisher).publishEvent(new TaskStatusChangedEvent(1L, "PENDING", "FAILED"));
    }

    @Test
    @DisplayName("测试设置任务错误信息时任务不存在")
    public void shouldThrowException_whenSetErrorForNonExistentTask() {
        // Given
        when(taskMapper.selectById(999L)).thenReturn(null);

        // When & Then
        assertThatThrownBy(() -> taskService.setTaskError(999L, "Error"))
            .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.TASK_NOT_FOUND.getCode());
        
        verify(taskMapper, never()).updateById(any(Task.class));
    }

    @Test
    @DisplayName("测试分页查询空结果")
    public void shouldReturnEmptyPage_whenNoTasksFound() {
        // Given
        Page<Task> emptyPage = new Page<>(1, 10);
        emptyPage.setRecords(Arrays.asList());
        emptyPage.setTotal(0);

        when(taskMapper.selectTaskPage(any(Page.class), isNull(), isNull())).thenReturn(emptyPage);

        // When
        IPage<Task> result = taskService.listTasks(0, 10, null, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(0);
    }

    @Test
    @DisplayName("测试根据状态查询空结果")
    public void shouldReturnEmptyList_whenNoTasksWithStatus() {
        // Given
        when(taskMapper.findByStatus("PENDING")).thenReturn(Arrays.asList());

        // When
        List<Task> result = taskService.listTasksByStatus("PENDING");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("重复任务编码转为业务异常")
    public void shouldThrowWhenTaskCodeDuplicated() {
        when(taskMapper.insert(any(Task.class))).thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> taskService.createTask(testRequest))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.RESOURCE_ALREADY_EXISTS.getCode())
                .hasMessage("任务编码已存在");
    }

    @Test
    @DisplayName("依赖列表序列化成 JSON 数组，空列表写成 null")
    public void shouldSerializeDependenciesAsJsonArray() {
        testRequest.setDependencies(List.of("mysql:8.0", "redis:7.0"));
        when(taskMapper.insert(any(Task.class))).thenReturn(1);

        taskService.createTask(testRequest);

        testRequest.setDependencies(List.of());
        taskService.createTask(testRequest);

        ArgumentCaptor<Task> inserted = ArgumentCaptor.forClass(Task.class);
        verify(taskMapper, times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues().get(0).getDependencies()).isEqualTo("[\"mysql:8.0\",\"redis:7.0\"]");
        assertThat(inserted.getAllValues().get(1).getDependencies()).isNull();
    }

    @Test
    @DisplayName("未知任务状态被拒绝")
    public void shouldRejectUnknownStatus() {
        assertThatThrownBy(() -> taskService.updateTaskStatus(1L, "NOT_A_STATUS"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.TASK_STATUS_INVALID.getCode());

        verify(taskMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("任务状态进度按状态估算")
    public void shouldDeriveProgressFromStatus() {
        Map<String, Integer> expected = Map.of(
                "PENDING", 0,
                "QUEUED", 10,
                "RUNNING", 50,
                "SUCCESS", 100,
                "FAILED", 100,
                "CANCELLED", 100
        );
        long id = 1L;
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            Task task = TaskBuilder.aTask().withId(id).withStatus(entry.getKey()).build();
            when(taskMapper.selectById(id)).thenReturn(task);

            TaskStatusResponse response = taskService.getTaskStatus(id);

            assertThat(response.getTaskId()).isEqualTo(id);
            assertThat(response.getStatus()).isEqualTo(entry.getKey());
            assertThat(response.getProgress()).isEqualTo(entry.getValue());
            id++;
        }
    }

    @Test
    @DisplayName("未知状态进度为 0")
    public void shouldUseZeroProgressForUnknownStatus() {
        Task task = TaskBuilder.aTask().withId(3L).withStatus("PAUSED").build();
        when(taskMapper.selectById(3L)).thenReturn(task);

        TaskStatusResponse response = taskService.getTaskStatus(3L);

        assertThat(response.getProgress()).isZero();
    }

    @Test
    @DisplayName("查询不存在的任务状态返回任务不存在")
    public void shouldThrowWhenTaskStatusMissing() {
        when(taskMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> taskService.getTaskStatus(9L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.TASK_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("非法 tail 或 since 在查库前拒绝")
    public void shouldRejectBadTailAndSinceBeforeLookup() {
        assertThatThrownBy(() -> taskService.getTaskLogs(1L, 0, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.VALIDATION_ERROR.getCode())
                .hasMessage(ContainerLogWindow.TAIL_RANGE_MESSAGE);
        assertThatThrownBy(() -> taskService.getTaskLogs(1L, 5001, "2026-10-09T00:00:00Z"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ContainerLogWindow.TAIL_RANGE_MESSAGE);
        assertThatThrownBy(() -> taskService.getTaskLogs(1L, 100, "yesterday"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.VALIDATION_ERROR.getCode())
                .hasMessage(ContainerLogWindow.SINCE_FORMAT_MESSAGE);

        verifyNoInteractions(taskMapper);
        verifyNoInteractions(dockerService);
    }

    @Test
    @DisplayName("没有环境时回退到错误信息")
    public void shouldFallBackToErrorMessageWhenEnvironmentMissing() {
        Task task = TaskBuilder.aTask().withId(4L).withEnvironmentId(null).withErrorMessage("boom").build();
        when(taskMapper.selectById(4L)).thenReturn(task);

        TaskLogsResponse response = taskService.getTaskLogs(4L, 100, null);

        assertThat(response.getLogs()).isEqualTo("boom");
        assertThat(response.isHasMore()).isFalse();
        assertThat(response.getNextSince()).isNull();
        verifyNoInteractions(dockerService);
    }

    @Test
    @DisplayName("没有环境也没有错误信息时日志为空")
    public void shouldReturnEmptyLogsWhenNothingToShow() {
        Task task = TaskBuilder.aTask().withId(4L).withEnvironmentId(null).withErrorMessage("  ").build();
        when(taskMapper.selectById(4L)).thenReturn(task);

        TaskLogsResponse response = taskService.getTaskLogs(4L, 1, "2026-10-09T08:00:00");

        assertThat(response.getLogs()).isEmpty();
        assertThat(response.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("环境没有容器时回退到错误信息")
    public void shouldFallBackWhenContainerListEmpty() {
        Task task = TaskBuilder.aTask().withId(5L).withEnvironmentId(9L).withErrorMessage("env empty").build();
        when(taskMapper.selectById(5L)).thenReturn(task);
        when(dockerService.getContainersByEnvironmentId(9L)).thenReturn(List.of());

        TaskLogsResponse response = taskService.getTaskLogs(5L, 100, null);

        assertThat(response.getLogs()).isEqualTo("env empty");
        assertThat(response.isHasMore()).isFalse();
        verify(dockerService, never()).getContainerLogs(anyString(), anyInt());
    }

    @Test
    @DisplayName("查询容器失败时回退到错误信息")
    public void shouldFallBackWhenContainerQueryFails() {
        Task task = TaskBuilder.aTask().withId(5L).withEnvironmentId(9L).withErrorMessage("query failed").build();
        when(taskMapper.selectById(5L)).thenReturn(task);
        when(dockerService.getContainersByEnvironmentId(9L)).thenThrow(new RuntimeException("db down"));

        TaskLogsResponse response = taskService.getTaskLogs(5L, 100, null);

        assertThat(response.getLogs()).isEqualTo("query failed");
        assertThat(response.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("拼接容器日志并在行数达到 tail 时标记 hasMore")
    public void shouldCollectContainerLogsWithHeaders() {
        Task task = TaskBuilder.aTask().withId(6L).withEnvironmentId(8L).withErrorMessage("should-not-appear").build();
        when(taskMapper.selectById(6L)).thenReturn(task);

        Container mysql = new Container();
        mysql.setContainerId("c1");
        mysql.setContainerName("mysql");
        Container redis = new Container();
        redis.setContainerId("c2");
        redis.setContainerName("redis");
        Container blank = new Container();
        blank.setContainerId(" ");
        when(dockerService.getContainersByEnvironmentId(8L)).thenReturn(Arrays.asList(null, blank, mysql, redis));
        when(dockerService.getContainerLogs("c1", 2)).thenReturn("a\nb\n");
        when(dockerService.getContainerLogs("c2", 2)).thenReturn("only-one");

        TaskLogsResponse response = taskService.getTaskLogs(6L, 2, null);

        assertThat(response.getLogs()).isEqualTo(
                "--- container mysql ---\na\nb\n--- container redis ---\nonly-one");
        assertThat(response.isHasMore()).isTrue();
        assertThat(response.getNextSince()).isNull();
        assertThat(response.getLogs()).doesNotContain("should-not-appear");
    }

    @Test
    @DisplayName("容器名为空时用容器 ID，读取失败的容器被跳过")
    public void shouldSkipFailedContainerAndUseIdAsName() {
        Task task = TaskBuilder.aTask().withId(7L).withEnvironmentId(8L).withErrorMessage("kept-out").build();
        when(taskMapper.selectById(7L)).thenReturn(task);

        Container ok = new Container();
        ok.setContainerId("cid-ok");
        Container bad = new Container();
        bad.setContainerId("cid-bad");
        bad.setContainerName("broken");
        when(dockerService.getContainersByEnvironmentId(8L)).thenReturn(List.of(bad, ok));
        when(dockerService.getContainerLogs("cid-bad", 100)).thenThrow(new RuntimeException("docker log down"));
        when(dockerService.getContainerLogs("cid-ok", 100)).thenReturn("alive");

        TaskLogsResponse response = taskService.getTaskLogs(7L, 100, null);

        assertThat(response.getLogs()).isEqualTo("--- container cid-ok ---\nalive");
        assertThat(response.isHasMore()).isFalse();
        assertThat(response.getLogs()).doesNotContain("broken").doesNotContain("kept-out");
    }

    @Test
    @DisplayName("全部容器日志读取失败时不回退错误信息")
    public void shouldReturnEmptyWhenEveryContainerLogFails() {
        Task task = TaskBuilder.aTask().withId(7L).withEnvironmentId(8L).withErrorMessage("boom").build();
        when(taskMapper.selectById(7L)).thenReturn(task);
        Container bad = new Container();
        bad.setContainerId("cid-bad");
        bad.setContainerName("broken");
        when(dockerService.getContainersByEnvironmentId(8L)).thenReturn(List.of(bad));
        when(dockerService.getContainerLogs("cid-bad", 100)).thenThrow(new RuntimeException("docker log down"));

        TaskLogsResponse response = taskService.getTaskLogs(7L, 100, null);

        assertThat(response.getLogs()).isEmpty();
        assertThat(response.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("since 传给 Docker，混有无时间戳的行时保留正文时间戳")
    public void shouldPassSinceAndKeepOriginalTimestamps() {
        Task task = TaskBuilder.aTask().withId(8L).withEnvironmentId(3L).build();
        when(taskMapper.selectById(8L)).thenReturn(task);
        Container mysql = new Container();
        mysql.setContainerId("c1");
        mysql.setContainerName("mysql");
        when(dockerService.getContainersByEnvironmentId(3L)).thenReturn(List.of(mysql));
        Instant since = Instant.parse("2026-10-09T08:00:00Z");
        when(dockerService.getContainerLogs("c1", 3, since)).thenReturn(
                "2026-10-09T07:00:00.000000000Z old\nplain\n2026-10-09T09:00:00.000000000Z new\n");

        TaskLogsResponse response = taskService.getTaskLogs(8L, 3, "2026-10-09T08:00:00Z");

        assertThat(response.getLogs()).isEqualTo(
                "--- container mysql ---\nplain\n2026-10-09T09:00:00.000000000Z new");
        assertThat(response.isHasMore()).isTrue();
        assertThat(response.getNextSince()).isNull();
        verify(dockerService).getContainerLogs("c1", 3, since);
        verify(dockerService, never()).getContainerLogs(anyString(), anyInt());
    }

    @Test
    @DisplayName("每一行都带 Docker 时间戳时去掉一层，正文里的时间戳保留")
    public void shouldStripUniformDockerTimestampPrefixes() {
        Task task = TaskBuilder.aTask().withId(8L).withEnvironmentId(3L).build();
        when(taskMapper.selectById(8L)).thenReturn(task);
        Container mysql = new Container();
        mysql.setContainerId("c1");
        mysql.setContainerName("mysql");
        when(dockerService.getContainersByEnvironmentId(3L)).thenReturn(List.of(mysql));
        Instant since = Instant.parse("2026-10-09T08:00:00Z");
        when(dockerService.getContainerLogs("c1", 3, since)).thenReturn(
                "2026-10-09T07:00:00.000000000Z old\n"
                        + "2026-10-09T09:00:00.000000000Z plain\n"
                        + "2026-10-09T09:00:00.000000000Z 2026-10-09T09:00:01Z new\n");

        TaskLogsResponse response = taskService.getTaskLogs(8L, 3, "2026-10-09T08:00:00Z");

        assertThat(response.getLogs()).isEqualTo(
                "--- container mysql ---\nplain\n2026-10-09T09:00:01Z new");
        assertThat(response.isHasMore()).isTrue();
        assertThat(response.getNextSince()).isEqualTo("2026-10-09T09:00:00.000000001Z");
    }

    @Test
    @DisplayName("同一秒里 Docker 会重给已返回的行，nextSince 把它们丢掉并留下更晚的行")
    public void shouldAdvanceCursorPastReturnedLinesInTheSameSecond() {
        Task task = TaskBuilder.aTask().withId(8L).withEnvironmentId(3L).build();
        when(taskMapper.selectById(8L)).thenReturn(task);
        Container mysql = new Container();
        mysql.setContainerId("c1");
        mysql.setContainerName("mysql");
        when(dockerService.getContainersByEnvironmentId(3L)).thenReturn(List.of(mysql));

        Instant origin = Instant.parse("1970-01-01T00:00:00Z");
        when(dockerService.getContainerLogs("c1", 100, origin)).thenReturn(
                "2026-10-09T08:00:00.100000000Z one\n2026-10-09T08:00:00.500000000Z two\n");

        TaskLogsResponse first = taskService.getTaskLogs(8L, 100, "1970-01-01T00:00:00Z");

        assertThat(first.getLogs()).isEqualTo("--- container mysql ---\none\ntwo");
        assertThat(first.getNextSince()).isEqualTo("2026-10-09T08:00:00.500000001Z");

        Instant cursor = Instant.parse(first.getNextSince());
        when(dockerService.getContainerLogs("c1", 5000, cursor)).thenReturn(
                "2026-10-09T08:00:00.100000000Z one\n"
                        + "2026-10-09T08:00:00.500000000Z two\n"
                        + "2026-10-09T08:00:00.800000000Z three\n");

        TaskLogsResponse second = taskService.getTaskLogs(8L, 5000, first.getNextSince());

        assertThat(second.getLogs()).isEqualTo("--- container mysql ---\nthree");
        assertThat(second.getNextSince()).isEqualTo("2026-10-09T08:00:00.800000001Z");
        verify(dockerService).getContainerLogs("c1", 5000, cursor);
    }

    @Test
    @DisplayName("没有新行时 nextSince 保持不变")
    public void shouldKeepCursorWhenNoNewLines() {
        Task task = TaskBuilder.aTask().withId(8L).withEnvironmentId(3L).build();
        when(taskMapper.selectById(8L)).thenReturn(task);
        Container mysql = new Container();
        mysql.setContainerId("c1");
        mysql.setContainerName("mysql");
        when(dockerService.getContainersByEnvironmentId(3L)).thenReturn(List.of(mysql));
        String cursor = "2026-10-09T08:00:00.500000001Z";
        when(dockerService.getContainerLogs("c1", 5000, Instant.parse(cursor))).thenReturn(
                "2026-10-09T08:00:00.100000000Z one\n2026-10-09T08:00:00.500000000Z two\n");

        TaskLogsResponse response = taskService.getTaskLogs(8L, 5000, cursor);

        assertThat(response.getLogs()).isEmpty();
        assertThat(response.getNextSince()).isEqualTo(cursor);
    }

    @Test
    @DisplayName("容器日志读取失败时不给出续传游标")
    public void shouldOmitCursorWhenContainerReadFails() {
        Task task = TaskBuilder.aTask().withId(8L).withEnvironmentId(3L).build();
        when(taskMapper.selectById(8L)).thenReturn(task);
        Container bad = new Container();
        bad.setContainerId("c1");
        bad.setContainerName("mysql");
        Container ok = new Container();
        ok.setContainerId("c2");
        ok.setContainerName("redis");
        when(dockerService.getContainersByEnvironmentId(3L)).thenReturn(List.of(bad, ok));
        Instant since = Instant.parse("2026-10-09T08:00:00Z");
        when(dockerService.getContainerLogs("c1", 10, since)).thenThrow(new RuntimeException("down"));
        when(dockerService.getContainerLogs("c2", 10, since)).thenReturn(
                "2026-10-09T08:00:01.000000000Z up\n");

        TaskLogsResponse response = taskService.getTaskLogs(8L, 10, "2026-10-09T08:00:00Z");

        assertThat(response.getLogs()).isEqualTo("--- container redis ---\nup");
        assertThat(response.getNextSince()).isNull();
    }

    @Test
    @DisplayName("日志不存在时查询日志失败")
    public void shouldThrowWhenTaskLogsMissing() {
        when(taskMapper.selectById(44L)).thenReturn(null);

        assertThatThrownBy(() -> taskService.getTaskLogs(44L, 100, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.TASK_NOT_FOUND.getCode());
        verifyNoInteractions(dockerService);
    }

    @Test
    @DisplayName("状态相同则不写库也不发事件")
    public void shouldSkipUpdateAndEventWhenStatusUnchanged() {
        when(taskMapper.selectById(1L)).thenReturn(testTask);

        taskService.updateTaskStatus(1L, "PENDING");

        verify(taskMapper, never()).updateById(any(Task.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("已经失败时更新错误信息但不重复发状态事件")
    public void shouldNotPublishWhenAlreadyFailed() {
        Task failedTask = TaskBuilder.aTask().withId(1L).failed().build();
        when(taskMapper.selectById(1L)).thenReturn(failedTask);
        when(taskMapper.updateById(any(Task.class))).thenReturn(1);

        taskService.setTaskError(1L, "again");

        verify(taskMapper).updateById(argThat((Task task) -> "again".equals(task.getErrorMessage())));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("优先级缺省为 5")
    public void shouldDefaultPriorityWhenMissing() {
        testRequest.setPriority(null);
        when(taskMapper.insert(any(Task.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Task.class).setId(4L);
            return 1;
        });

        Task result = taskService.createTask(testRequest);

        assertThat(result.getPriority()).isEqualTo(5);
        verify(eventPublisher).publishEvent(new TaskCreatedEvent(4L));
    }

    @Test
    @DisplayName("比较并设置状态成功时发事件，未命中时不发")
    public void shouldCompareAndSetStatus() {
        when(taskMapper.compareAndSetStatus(eq(1L), eq("PENDING"), eq("QUEUED"), isNull(), isNull()))
                .thenReturn(1);

        assertThat(taskService.transitionStatus(1L, "PENDING", "QUEUED")).isTrue();
        verify(eventPublisher).publishEvent(new TaskStatusChangedEvent(1L, "PENDING", "QUEUED"));

        when(taskMapper.compareAndSetStatus(eq(1L), eq("QUEUED"), eq("RUNNING"), any(LocalDateTime.class), isNull()))
                .thenReturn(0);
        assertThat(taskService.transitionStatus(1L, "QUEUED", "RUNNING")).isFalse();
        verify(eventPublisher, never()).publishEvent(new TaskStatusChangedEvent(1L, "QUEUED", "RUNNING"));

        when(taskMapper.compareAndSetStatus(eq(2L), eq("RUNNING"), eq("SUCCESS"), isNull(), any(LocalDateTime.class)))
                .thenReturn(1);
        assertThat(taskService.transitionStatus(2L, "RUNNING", "SUCCESS")).isTrue();
        verify(taskMapper).compareAndSetStatus(eq(2L), eq("RUNNING"), eq("SUCCESS"), isNull(), any(LocalDateTime.class));
        verify(eventPublisher).publishEvent(new TaskStatusChangedEvent(2L, "RUNNING", "SUCCESS"));
    }

    @Test
    @DisplayName("比较并设置遇到未知状态时拒绝")
    public void shouldRejectUnknownTransition() {
        assertThatThrownBy(() -> taskService.transitionStatus(1L, "PENDING", "NOPE"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.TASK_STATUS_INVALID.getCode());
        verify(taskMapper, never()).compareAndSetStatus(any(), any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("关联环境只更新环境 ID，任务不存在时 404")
    public void shouldAttachEnvironment() {
        when(taskMapper.updateEnvironmentId(1L, 9L)).thenReturn(1);

        taskService.attachEnvironment(1L, 9L);

        verify(taskMapper).updateEnvironmentId(1L, 9L);
        verify(eventPublisher, never()).publishEvent(any());

        when(taskMapper.updateEnvironmentId(2L, 3L)).thenReturn(0);
        assertThatThrownBy(() -> taskService.attachEnvironment(2L, 3L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.TASK_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("分页参数和过滤枚举不合法时 400")
    public void shouldRejectBadListQuery() {
        assertThatThrownBy(() -> taskService.listTasks(-1, 20, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage(TaskService.PAGE_NEGATIVE_MESSAGE);
        assertThatThrownBy(() -> taskService.listTasks(0, 501, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage(TaskService.PAGE_SIZE_MESSAGE);
        assertThatThrownBy(() -> taskService.listTasks(0, 20, "NOPE", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage(TaskService.STATUS_FILTER_MESSAGE);
        assertThatThrownBy(() -> taskService.listTasks(0, 20, null, "WEIRD"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(TaskService.TEST_TYPE_FILTER_MESSAGE);

        verify(taskMapper, never()).selectTaskPage(any(), any(), any());
    }

    @Test
    @DisplayName("排队中的任务可以取消，且没有环境")
    public void shouldCancelQueuedTaskWithoutEnvironment() {
        Task queued = TaskBuilder.aTask().withId(1L).withStatus("QUEUED").withEnvironmentId(null).build();
        queued.setQueuedAt(LocalDateTime.of(2026, 10, 10, 1, 0));
        when(taskMapper.selectById(1L)).thenReturn(queued);
        when(taskMapper.updateById(any(Task.class))).thenReturn(1);

        taskService.cancelTask(1L);

        verify(taskMapper).updateById(argThat((Task task) ->
                "CANCELLED".equals(task.getStatus())
                        && task.getCompletedAt() != null
                        && task.getEnvironmentId() == null));
        verify(eventPublisher).publishEvent(new TaskStatusChangedEvent(1L, "QUEUED", "CANCELLED"));
        verifyNoInteractions(dockerService);
    }

    @Test
    @DisplayName("排队中的任务除取消外回报状态返回 400")
    public void shouldRejectStatusReportForQueuedTaskUnlessCancel() {
        Task queued = TaskBuilder.aTask().withId(1L).withStatus("QUEUED").build();
        when(taskMapper.selectById(1L)).thenReturn(queued);

        assertThatThrownBy(() -> taskService.updateTaskStatus(1L, "SUCCESS"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OPERATION_NOT_ALLOWED.getCode())
                .hasMessage("排队中的任务只能取消");
        assertThatThrownBy(() -> taskService.updateTaskStatus(1L, "FAILED"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OPERATION_NOT_ALLOWED.getCode());
        assertThatThrownBy(() -> taskService.updateTaskStatus(1L, "RUNNING"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OPERATION_NOT_ALLOWED.getCode());
        verify(taskMapper, never()).updateById(any(Task.class));

        when(taskMapper.updateById(any(Task.class))).thenReturn(1);
        taskService.updateTaskStatus(1L, "CANCELLED");
        verify(taskMapper).updateById(argThat((Task task) -> "CANCELLED".equals(task.getStatus())));
    }

    @Test
    @DisplayName("排队位次按优先级和排队时间计算")
    public void shouldNumberQueueByPriorityThenQueuedAt() {
        Task earlyLow = TaskBuilder.aTask().withId(2L).withStatus("QUEUED").withPriority(1).build();
        earlyLow.setQueuedAt(LocalDateTime.of(2026, 10, 10, 1, 0));
        Task lateHigh = TaskBuilder.aTask().withId(8L).withStatus("QUEUED").withPriority(9).build();
        lateHigh.setQueuedAt(LocalDateTime.of(2026, 10, 10, 3, 0));
        Task earlierHigh = TaskBuilder.aTask().withId(4L).withStatus("QUEUED").withPriority(9).build();
        earlierHigh.setQueuedAt(LocalDateTime.of(2026, 10, 10, 2, 0));
        when(taskMapper.findWaitingQueued()).thenReturn(List.of(earlyLow, lateHigh, earlierHigh));

        assertThat(taskService.queuePositions())
                .containsExactly(
                        Map.entry(4L, 1),
                        Map.entry(8L, 2),
                        Map.entry(2L, 3));
    }

    @Test
    @DisplayName("进入排队和出队都是条件更新")
    public void shouldMarkAndClaimQueuedWithEvents() {
        LocalDateTime queuedAt = LocalDateTime.of(2026, 10, 10, 4, 0);
        when(taskMapper.markQueued(1L, queuedAt)).thenReturn(1);
        assertThat(taskService.markQueued(1L, queuedAt)).isTrue();
        verify(eventPublisher).publishEvent(new TaskStatusChangedEvent(1L, "PENDING", "QUEUED"));

        when(taskMapper.markQueued(1L, queuedAt)).thenReturn(0);
        assertThat(taskService.markQueued(1L, queuedAt)).isFalse();
        verify(eventPublisher, times(1)).publishEvent(new TaskStatusChangedEvent(1L, "PENDING", "QUEUED"));

        when(taskMapper.claimQueued(1L)).thenReturn(1);
        assertThat(taskService.claimQueued(1L)).isTrue();
        verify(eventPublisher).publishEvent(new TaskStatusChangedEvent(1L, "QUEUED", "PENDING"));

        when(taskMapper.claimQueued(1L)).thenReturn(0);
        assertThat(taskService.claimQueued(1L)).isFalse();
        verify(eventPublisher, times(1)).publishEvent(new TaskStatusChangedEvent(1L, "QUEUED", "PENDING"));
    }
}
