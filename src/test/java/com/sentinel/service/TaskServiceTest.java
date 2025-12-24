package com.sentinel.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sentinel.config.BusinessException;
import com.sentinel.domain.Task;
import com.sentinel.dto.CreateTaskRequest;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.TaskMapper;
import com.sentinel.testutil.CreateTaskRequestBuilder;
import com.sentinel.testutil.TaskBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

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

        when(taskMapper.selectTaskPage(any(Page.class), isNull())).thenReturn(page);

        // When
        IPage<Task> result = taskService.listTasks(1, 10, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getTotal()).isEqualTo(2);
        
        verify(taskMapper, times(1)).selectTaskPage(any(Page.class), isNull());
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

        when(taskMapper.selectTaskPage(any(Page.class), eq("RUNNING"))).thenReturn(page);

        // When
        IPage<Task> result = taskService.listTasks(1, 10, "RUNNING");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getRecords()).allMatch(task -> "RUNNING".equals(task.getStatus()));
        
        verify(taskMapper, times(1)).selectTaskPage(any(Page.class), eq("RUNNING"));
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

        when(taskMapper.selectTaskPage(any(Page.class), isNull())).thenReturn(emptyPage);

        // When
        IPage<Task> result = taskService.listTasks(1, 10, null);

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
}
