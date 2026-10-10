package com.sentinel.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.domain.Task;
import com.sentinel.dto.CreateTaskRequest;
import com.sentinel.dto.TaskLogsResponse;
import com.sentinel.dto.TaskStatusResponse;
import com.sentinel.enums.ErrorCode;
import com.sentinel.service.ContainerLogWindow;
import com.sentinel.config.BusinessException;
import com.sentinel.config.GlobalExceptionHandler;
import com.sentinel.config.JwtAuthenticationFilter;
import com.sentinel.config.SecurityConfig;
import com.sentinel.config.properties.SecurityProperties;
import com.sentinel.mapper.UserMapper;
import com.sentinel.service.CacheService;
import com.sentinel.service.UserDetailsServiceImpl;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import com.sentinel.service.TaskService;
import com.sentinel.util.JwtUtil;
import com.sentinel.testutil.CreateTaskRequestBuilder;
import com.sentinel.testutil.TaskBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * TaskController 测试类
 * 测试任务管理相关的所有接口
 */
@WebMvcTest(TaskController.class)
@AutoConfigureMockMvc(addFilters = true)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class})
@EnableConfigurationProperties(SecurityProperties.class)
@DisplayName("TaskController 测试")
public class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TaskService taskService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private CacheService cacheService;

    @MockBean
    private UserMapper userMapper;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @Test
    @WithMockUser
    @DisplayName("测试创建任务成功")
    public void shouldCreateTask_whenValidRequest() throws Exception {
        // Given
        CreateTaskRequest request = CreateTaskRequestBuilder.aCreateTaskRequest()
            .withTaskCode("TEST-001")
            .withServiceId("user-service")
            .unitTest()
            .build();

        Task task = TaskBuilder.aTask()
            .withId(1L)
            .withTaskCode("TEST-001")
            .withServiceId("user-service")
            .pending()
            .build();

        when(taskService.createTask(any(CreateTaskRequest.class))).thenReturn(task);

        // When & Then
        mockMvc.perform(post("/api/tasks")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.taskCode").value("TEST-001"))
                .andExpect(jsonPath("$.data.serviceId").value("user-service"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.dependencies").isArray())
                .andExpect(jsonPath("$.data.dependencies[0]").value("mysql:8.0"));

        verify(taskService, times(1)).createTask(any(CreateTaskRequest.class));
    }

    @Test
    @WithMockUser
    @DisplayName("测试创建任务失败 - 缺少必填字段")
    public void shouldReturnValidationError_whenRequiredFieldsMissing() throws Exception {
        // Given
        CreateTaskRequest request = new CreateTaskRequest();
        // 不设置任何字段

        // When & Then
        mockMvc.perform(post("/api/tasks")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(taskService, never()).createTask(any(CreateTaskRequest.class));
    }

    @Test
    @WithMockUser
    @DisplayName("测试获取任务详情成功")
    public void shouldGetTask_whenValidTaskId() throws Exception {
        // Given
        Long taskId = 1L;
        Task task = TaskBuilder.aTask()
            .withId(taskId)
            .withTaskCode("TEST-001")
            .running()
            .build();

        when(taskService.getTaskById(taskId)).thenReturn(task);

        // When & Then
        mockMvc.perform(get("/api/tasks/{taskId}", taskId)
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.taskCode").value("TEST-001"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        verify(taskService, times(1)).getTaskById(taskId);
    }

    @Test
    @WithMockUser
    @DisplayName("测试获取任务详情失败 - 任务不存在")
    public void shouldReturnNotFound_whenTaskNotExists() throws Exception {
        // Given
        Long taskId = 999L;
        when(taskService.getTaskById(taskId))
            .thenThrow(new BusinessException(2001, "任务不存在"));

        // When & Then
        mockMvc.perform(get("/api/tasks/{taskId}", taskId)
                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.message").value("任务不存在"));

        verify(taskService, times(1)).getTaskById(taskId);
    }

    @Test
    @WithMockUser
    @DisplayName("测试分页查询任务列表")
    public void shouldListTasks_whenValidPagination() throws Exception {
        // Given
        Task task1 = TaskBuilder.aTask().withId(1L).pending().build();
        Task task2 = TaskBuilder.aTask().withId(2L).running().build();
        
        IPage<Task> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(task1, task2));
        page.setTotal(2);

        when(taskService.listTasks(0, 10, null, null)).thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/tasks")
                .with(csrf())
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(1));

        verify(taskService, times(1)).listTasks(0, 10, null, null);
    }

    @Test
    @WithMockUser
    @DisplayName("测试按状态过滤查询任务列表")
    public void shouldListTasks_whenFilterByStatus() throws Exception {
        // Given
        Task task1 = TaskBuilder.aTask().withId(1L).running().build();
        Task task2 = TaskBuilder.aTask().withId(2L).running().build();
        
        IPage<Task> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(task1, task2));
        page.setTotal(2);

        when(taskService.listTasks(0, 10, "RUNNING", "INTEGRATION_TEST")).thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/tasks")
                .with(csrf())
                .param("page", "0")
                .param("size", "10")
                .param("status", "RUNNING")
                .param("testType", "INTEGRATION_TEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].status").value("RUNNING"));

        verify(taskService, times(1)).listTasks(0, 10, "RUNNING", "INTEGRATION_TEST");
    }

    @Test
    @WithMockUser
    @DisplayName("测试根据状态查询任务列表")
    public void shouldListTasksByStatus_whenValidStatus() throws Exception {
        // Given
        Task task1 = TaskBuilder.aTask().withId(1L).success().build();
        Task task2 = TaskBuilder.aTask().withId(2L).success().build();
        List<Task> tasks = Arrays.asList(task1, task2);

        when(taskService.listTasksByStatus("SUCCESS")).thenReturn(tasks);

        // When & Then
        mockMvc.perform(get("/api/tasks/status/{status}", "SUCCESS")
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2));

        verify(taskService, times(1)).listTasksByStatus("SUCCESS");
    }

    @Test
    @WithMockUser
    @DisplayName("测试取消任务成功 - PENDING状态")
    public void shouldCancelTask_whenTaskIsPending() throws Exception {
        // Given
        Long taskId = 1L;
        doNothing().when(taskService).cancelTask(taskId);

        // When & Then
        mockMvc.perform(post("/api/tasks/{taskId}/cancel", taskId)
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("任务已取消"));

        verify(taskService, times(1)).cancelTask(taskId);
    }

    @Test
    @WithMockUser
    @DisplayName("测试取消任务失败 - 任务已完成")
    public void shouldReturnError_whenCancelCompletedTask() throws Exception {
        // Given
        Long taskId = 1L;
        doThrow(new BusinessException(5007, "操作不允许"))
            .when(taskService).cancelTask(taskId);

        // When & Then
        mockMvc.perform(post("/api/tasks/{taskId}/cancel", taskId)
                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5007));

        verify(taskService, times(1)).cancelTask(taskId);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("测试更新任务状态成功 - 管理员权限")
    public void shouldUpdateTaskStatus_whenUserIsAdmin() throws Exception {
        // Given
        Long taskId = 1L;
        String newStatus = "RUNNING";
        doNothing().when(taskService).updateTaskStatus(taskId, newStatus);

        // When & Then
        mockMvc.perform(put("/api/tasks/{taskId}/status", taskId)
                .with(csrf())
                .param("status", newStatus))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("任务状态已更新"));

        verify(taskService, times(1)).updateTaskStatus(taskId, newStatus);
    }

    @Test
    @DisplayName("更新任务状态无令牌时返回 401")
    public void shouldReturnUnauthorized_whenUpdateStatusWithoutToken() throws Exception {
        mockMvc.perform(put("/api/tasks/{taskId}/status", 1L)
                        .param("status", "RUNNING"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("未授权，请先登录"));

        verify(taskService, never()).updateTaskStatus(anyLong(), anyString());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("测试更新任务状态失败 - 非管理员权限")
    public void shouldReturnForbidden_whenUserIsNotAdmin() throws Exception {
        mockMvc.perform(put("/api/tasks/{taskId}/status", 1L)
                        .with(csrf())
                        .param("status", "RUNNING"))
                .andExpect(status().isForbidden());

        verify(taskService, never()).updateTaskStatus(anyLong(), anyString());
    }

    @Test
    @WithMockUser
    @DisplayName("测试分页参数默认值")
    public void shouldUseDefaultPaginationParams_whenNotProvided() throws Exception {
        // Given
        IPage<Task> page = new Page<>(1, 20);
        page.setRecords(Arrays.asList());
        page.setTotal(0);

        when(taskService.listTasks(0, 20, null, null)).thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/tasks")
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));

        verify(taskService, times(1)).listTasks(0, 20, null, null);
    }

    @Test
    @WithMockUser
    @DisplayName("测试数据返回格式正确性")
    public void shouldReturnCorrectDataFormat() throws Exception {
        // Given
        Task task = TaskBuilder.aTask()
            .withId(1L)
            .withTaskCode("TEST-001")
            .withServiceId("user-service")
            .withTestType("UNIT_TEST")
            .withPriority(5)
            .pending()
            .build();

        when(taskService.getTaskById(1L)).thenReturn(task);

        // When & Then
        mockMvc.perform(get("/api/tasks/{taskId}", 1L)
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.taskCode").isString())
                .andExpect(jsonPath("$.data.serviceId").isString())
                .andExpect(jsonPath("$.data.testType").isString())
                .andExpect(jsonPath("$.data.status").isString())
                .andExpect(jsonPath("$.data.priority").isNumber())
                .andExpect(jsonPath("$.data.dependencies").isArray())
                .andExpect(jsonPath("$.data.updatedAt").doesNotExist());
    }

    @Test
    @WithMockUser
    @DisplayName("非法分页参数返回 400")
    public void shouldRejectNonPositivePagination() throws Exception {
        mockMvc.perform(get("/api/tasks")
                        .with(csrf())
                        .param("page", "-1")
                        .param("size", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5004))
                .andExpect(jsonPath("$.message").value(TaskService.PAGE_NEGATIVE_MESSAGE));

        mockMvc.perform(get("/api/tasks")
                        .with(csrf())
                        .param("page", "0")
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5004))
                .andExpect(jsonPath("$.message").value(TaskService.PAGE_SIZE_MESSAGE));

        mockMvc.perform(get("/api/tasks")
                        .with(csrf())
                        .param("size", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5004))
                .andExpect(jsonPath("$.message").value(TaskService.PAGE_SIZE_MESSAGE));

        mockMvc.perform(get("/api/tasks")
                        .with(csrf())
                        .param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(TaskService.STATUS_FILTER_MESSAGE));

        mockMvc.perform(get("/api/tasks")
                        .with(csrf())
                        .param("testType", "WEIRD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(TaskService.TEST_TYPE_FILTER_MESSAGE));

        verify(taskService, never()).listTasks(anyInt(), anyInt(), any(), any());
    }

    @Test
    @WithMockUser
    @DisplayName("任务 ID 不是数字时返回 400")
    public void shouldReturnBadRequest_whenTaskIdIsNotNumeric() throws Exception {
        mockMvc.perform(get("/api/tasks/{taskId}", "abc").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5004))
                .andExpect(jsonPath("$.message").value("请求参数错误"));
    }

    @Test
    @WithMockUser
    @DisplayName("不支持的方法返回 405")
    public void shouldReturnMethodNotAllowed_whenPatchTask() throws Exception {
        mockMvc.perform(patch("/api/tasks/{taskId}", 1L).with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(5007))
                .andExpect(jsonPath("$.message").value("不支持的请求方法"));
    }

    @Test
    @WithMockUser
    @DisplayName("DELETE 取消任务成功")
    public void shouldCancelTask_whenDelete() throws Exception {
        doNothing().when(taskService).cancelTask(3L);

        mockMvc.perform(delete("/api/tasks/{taskId}", 3L).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("任务已取消"));

        verify(taskService).cancelTask(3L);
    }

    @Test
    @WithMockUser
    @DisplayName("DELETE 取消已结束任务返回 400")
    public void shouldRejectDeleteWhenTaskFinished() throws Exception {
        doThrow(new BusinessException(ErrorCode.OPERATION_NOT_ALLOWED, "任务已完成，无法取消"))
                .when(taskService).cancelTask(3L);

        mockMvc.perform(delete("/api/tasks/{taskId}", 3L).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5007))
                .andExpect(jsonPath("$.message").value("任务已完成，无法取消"));
    }

    @Test
    @WithMockUser
    @DisplayName("DELETE 取消不存在的任务返回 404")
    public void shouldReturnNotFoundWhenDeleteMissingTask() throws Exception {
        doThrow(new BusinessException(ErrorCode.TASK_NOT_FOUND))
                .when(taskService).cancelTask(9L);

        mockMvc.perform(delete("/api/tasks/{taskId}", 9L).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.message").value("任务不存在"));
    }

    @Test
    @WithMockUser
    @DisplayName("更新状态缺少 status 参数返回 400")
    public void shouldReturnBadRequest_whenStatusParamMissing() throws Exception {
        mockMvc.perform(put("/api/tasks/{taskId}/status", 1L).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5004))
                .andExpect(jsonPath("$.message").value("请求参数错误"));

        verify(taskService, never()).updateTaskStatus(anyLong(), anyString());
    }

    @Test
    @WithMockUser
    @DisplayName("重复任务编码返回 400")
    public void shouldReturnBadRequest_whenTaskCodeDuplicated() throws Exception {
        when(taskService.createTask(any())).thenThrow(new DuplicateKeyException("dup"));
        CreateTaskRequest request = CreateTaskRequestBuilder.aCreateTaskRequest().build();

        mockMvc.perform(post("/api/tasks")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5006))
                .andExpect(jsonPath("$.message").value("资源已存在"));
    }

    @Test
    @WithMockUser
    @DisplayName("数据约束失败返回 400 且不暴露 SQL")
    public void shouldReturnBadRequest_whenDataIntegrityViolated() throws Exception {
        when(taskService.createTask(any())).thenThrow(new DataIntegrityViolationException("bad sql"));
        CreateTaskRequest request = CreateTaskRequestBuilder.aCreateTaskRequest().build();

        mockMvc.perform(post("/api/tasks")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5004))
                .andExpect(jsonPath("$.message").value("请求数据不符合约束"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sql"))));
    }

    @Test
    @WithMockUser
    @DisplayName("超长编码、非法类型或越界优先级返回 400")
    public void shouldRejectOversizedOrIllegalCreateFields() throws Exception {
        CreateTaskRequest request = CreateTaskRequestBuilder.aCreateTaskRequest()
                .withTaskCode("T".repeat(65))
                .withTestType("WEIRD")
                .withPriority(0)
                .build();

        mockMvc.perform(post("/api/tasks")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5004))
                .andExpect(jsonPath("$.data.taskCode").value("任务编码长度不能超过64"))
                .andExpect(jsonPath("$.data.testType").value("测试类型不合法"))
                .andExpect(jsonPath("$.data.priority").value("优先级必须在1到10之间"));

        verify(taskService, never()).createTask(any());
    }

    @Test
    @WithMockUser
    @DisplayName("获取任务状态返回 taskId、status 和 progress")
    public void shouldReturnTaskStatus() throws Exception {
        TaskStatusResponse status = new TaskStatusResponse();
        status.setTaskId(8L);
        status.setStatus("RUNNING");
        status.setProgress(50);
        when(taskService.getTaskStatus(8L)).thenReturn(status);

        mockMvc.perform(get("/api/tasks/{taskId}/status", 8L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value(8))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.progress").value(50));

        verify(taskService).getTaskStatus(8L);
    }

    @Test
    @WithMockUser
    @DisplayName("任务状态不存在时返回 404")
    public void shouldReturnNotFoundWhenTaskStatusMissing() throws Exception {
        when(taskService.getTaskStatus(9L)).thenThrow(new BusinessException(ErrorCode.TASK_NOT_FOUND));

        mockMvc.perform(get("/api/tasks/{taskId}/status", 9L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.message").value("任务不存在"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("按状态列表、单任务状态和更新状态路径不冲突")
    public void statusRoutesDoNotClash() throws Exception {
        when(taskService.listTasksByStatus("RUNNING")).thenReturn(List.of());
        TaskStatusResponse status = new TaskStatusResponse();
        status.setTaskId(8L);
        status.setStatus("QUEUED");
        status.setProgress(10);
        when(taskService.getTaskStatus(8L)).thenReturn(status);

        mockMvc.perform(get("/api/tasks/status/{status}", "RUNNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/tasks/{taskId}/status", 8L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(8))
                .andExpect(jsonPath("$.data.progress").value(10));

        mockMvc.perform(put("/api/tasks/{taskId}/status", 8L)
                        .with(csrf())
                        .param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("任务状态已更新"));

        verify(taskService).listTasksByStatus("RUNNING");
        verify(taskService).getTaskStatus(8L);
        verify(taskService).updateTaskStatus(8L, "SUCCESS");
    }

    @Test
    @WithMockUser
    @DisplayName("获取任务日志返回 logs 和 hasMore，默认 tail 为 100")
    public void shouldReturnTaskLogs() throws Exception {
        TaskLogsResponse logs = new TaskLogsResponse();
        logs.setLogs("--- container mysql ---\nready");
        logs.setHasMore(false);
        when(taskService.getTaskLogs(4L, 100, null)).thenReturn(logs);

        mockMvc.perform(get("/api/tasks/{taskId}/logs", 4L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.logs").value("--- container mysql ---\nready"))
                .andExpect(jsonPath("$.data.hasMore").value(false));

        verify(taskService).getTaskLogs(4L, 100, null);
    }

    @Test
    @WithMockUser
    @DisplayName("任务日志不存在时返回 404")
    public void shouldReturnNotFoundWhenTaskLogsMissing() throws Exception {
        when(taskService.getTaskLogs(eq(9L), eq(100), isNull()))
                .thenThrow(new BusinessException(ErrorCode.TASK_NOT_FOUND));

        mockMvc.perform(get("/api/tasks/{taskId}/logs", 9L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.message").value("任务不存在"));
    }

    @Test
    @WithMockUser
    @DisplayName("tail 超出 1 到 5000 时返回 400")
    public void shouldRejectTailOutOfRange() throws Exception {
        mockMvc.perform(get("/api/tasks/{taskId}/logs", 1L).param("tail", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5004))
                .andExpect(jsonPath("$.message").value(ContainerLogWindow.TAIL_RANGE_MESSAGE));

        mockMvc.perform(get("/api/tasks/{taskId}/logs", 1L).param("tail", "5001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5004))
                .andExpect(jsonPath("$.message").value(ContainerLogWindow.TAIL_RANGE_MESSAGE));

        mockMvc.perform(get("/api/tasks/{taskId}/logs", 1L).param("tail", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5004));

        verify(taskService, never()).getTaskLogs(anyLong(), anyInt(), any());
    }

    @Test
    @WithMockUser
    @DisplayName("非法 since 返回 400，合法 since 传给服务")
    public void shouldRejectBadSinceAndPassValidSince() throws Exception {
        mockMvc.perform(get("/api/tasks/{taskId}/logs", 1L).param("since", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5004))
                .andExpect(jsonPath("$.message").value(ContainerLogWindow.SINCE_FORMAT_MESSAGE));

        TaskLogsResponse logs = new TaskLogsResponse();
        logs.setLogs("");
        logs.setHasMore(true);
        when(taskService.getTaskLogs(1L, 20, "2026-10-09T08:00:00Z")).thenReturn(logs);

        mockMvc.perform(get("/api/tasks/{taskId}/logs", 1L)
                        .param("tail", "20")
                        .param("since", "2026-10-09T08:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logs").value(""))
                .andExpect(jsonPath("$.data.hasMore").value(true));

        verify(taskService, never()).getTaskLogs(eq(1L), anyInt(), eq("yesterday"));
        verify(taskService).getTaskLogs(1L, 20, "2026-10-09T08:00:00Z");
    }
}
