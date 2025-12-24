package com.sentinel.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.domain.Task;
import com.sentinel.dto.CreateTaskRequest;
import com.sentinel.config.BusinessException;
import com.sentinel.config.GlobalExceptionHandler;
import com.sentinel.service.TaskService;
import com.sentinel.util.JwtUtil;
import com.sentinel.config.JwtAuthenticationFilter;
import com.sentinel.testutil.CreateTaskRequestBuilder;
import com.sentinel.testutil.TaskBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
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
    private JwtAuthenticationFilter jwtAuthenticationFilter;

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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.taskCode").value("TEST-001"))
                .andExpect(jsonPath("$.data.serviceId").value("user-service"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

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
                .andExpect(status().isBadRequest())
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

        when(taskService.listTasks(1, 10, null)).thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/tasks")
                .with(csrf())
                .param("pageNum", "1")
                .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.total").value(2));

        verify(taskService, times(1)).listTasks(1, 10, null);
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

        when(taskService.listTasks(1, 10, "RUNNING")).thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/tasks")
                .with(csrf())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("status", "RUNNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").value(2));

        verify(taskService, times(1)).listTasks(1, 10, "RUNNING");
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
    @WithMockUser(roles = "USER")
    @DisplayName("测试更新任务状态失败 - 非管理员权限")
    @org.junit.jupiter.api.Disabled("需要启用 Security 过滤器才能测试权限控制")
    public void shouldReturnForbidden_whenUserIsNotAdmin() throws Exception {
        // Given
        Long taskId = 1L;
        String newStatus = "RUNNING";

        // When & Then
        mockMvc.perform(put("/api/tasks/{taskId}/status", taskId)
                .with(csrf())
                .param("status", newStatus))
                .andExpect(status().isForbidden());

        verify(taskService, never()).updateTaskStatus(anyLong(), anyString());
    }

    @Test
    @WithMockUser
    @DisplayName("测试分页参数默认值")
    public void shouldUseDefaultPaginationParams_whenNotProvided() throws Exception {
        // Given
        IPage<Task> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList());
        page.setTotal(0);

        when(taskService.listTasks(1, 10, null)).thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/tasks")
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(taskService, times(1)).listTasks(1, 10, null);
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
                .andExpect(jsonPath("$.data.priority").isNumber());
    }
}
