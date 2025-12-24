package com.sentinel.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.BaseTest;
import com.sentinel.config.EmbeddedRedisConfig;
import com.sentinel.dto.CreateTaskRequest;
import com.sentinel.dto.LoginRequest;
import com.sentinel.testutil.CreateTaskRequestBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 端到端集成测试
 * 测试完整的业务流程：从用户登录到任务创建、查询、更新的完整流程
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedRedisConfig.class)
@Transactional
@Disabled("需要 Mock 外部服务（AI API、Docker API），暂时禁用")
@DisplayName("端到端集成测试")
public class EndToEndIntegrationTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("测试完整的用户登录和任务管理流程")
    public void shouldCompleteFullWorkflow_fromLoginToTaskManagement() throws Exception {
        // Step 1: 用户登录
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("testpass");

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andReturn();

        String loginResponse = loginResult.getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(loginResponse)
                .get("data")
                .get("accessToken")
                .asText();

        // Step 2: 使用Token创建任务
        CreateTaskRequest createRequest = CreateTaskRequestBuilder.aCreateTaskRequest()
                .withTaskCode("E2E-TEST-001")
                .withServiceId("user-service")
                .unitTest()
                .build();

        MvcResult createResult = mockMvc.perform(post("/api/tasks")
                .with(csrf())
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.taskCode").value("E2E-TEST-001"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();

        String createResponse = createResult.getResponse().getContentAsString();
        Long taskId = objectMapper.readTree(createResponse)
                .get("data")
                .get("id")
                .asLong();

        // Step 3: 查询任务详情
        mockMvc.perform(get("/api/tasks/{taskId}", taskId)
                .with(csrf())
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(taskId))
                .andExpect(jsonPath("$.data.taskCode").value("E2E-TEST-001"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        // Step 4: 查询任务列表
        mockMvc.perform(get("/api/tasks")
                .with(csrf())
                .header("Authorization", "Bearer " + accessToken)
                .param("pageNum", "1")
                .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());

        // Step 5: 取消任务
        mockMvc.perform(post("/api/tasks/{taskId}/cancel", taskId)
                .with(csrf())
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("任务已取消"));

        // Step 6: 验证任务状态已更新
        mockMvc.perform(get("/api/tasks/{taskId}", taskId)
                .with(csrf())
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // Step 7: 用户登出
        mockMvc.perform(post("/api/auth/logout")
                .with(csrf())
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("测试未认证用户访问受保护资源")
    public void shouldReturnUnauthorized_whenAccessProtectedResourceWithoutToken() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/tasks")
                .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("测试使用无效Token访问受保护资源")
    public void shouldReturnUnauthorized_whenAccessWithInvalidToken() throws Exception {
        // Given
        String invalidToken = "invalid.token.here";

        // When & Then
        mockMvc.perform(get("/api/tasks")
                .with(csrf())
                .header("Authorization", "Bearer " + invalidToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("测试健康检查接口无需认证")
    public void shouldAccessHealthEndpoint_withoutAuthentication() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("测试创建多个任务并分页查询")
    public void shouldCreateMultipleTasksAndQueryWithPagination() throws Exception {
        // Step 1: 登录
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("testpass");

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("data")
                .get("accessToken")
                .asText();

        // Step 2: 创建多个任务
        for (int i = 1; i <= 5; i++) {
            CreateTaskRequest request = CreateTaskRequestBuilder.aCreateTaskRequest()
                    .withTaskCode("E2E-MULTI-" + i)
                    .withServiceId("test-service-" + i)
                    .unitTest()
                    .build();

            mockMvc.perform(post("/api/tasks")
                    .with(csrf())
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        // Step 3: 分页查询任务
        mockMvc.perform(get("/api/tasks")
                .with(csrf())
                .header("Authorization", "Bearer " + accessToken)
                .param("pageNum", "1")
                .param("pageSize", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records.length()").value(3))
                .andExpect(jsonPath("$.data.total").value(5));
    }

    @Test
    @DisplayName("测试按状态过滤任务")
    public void shouldFilterTasksByStatus() throws Exception {
        // Step 1: 登录
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("testpass");

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("data")
                .get("accessToken")
                .asText();

        // Step 2: 创建PENDING状态的任务
        CreateTaskRequest request1 = CreateTaskRequestBuilder.aCreateTaskRequest()
                .withTaskCode("E2E-FILTER-1")
                .unitTest()
                .build();

        mockMvc.perform(post("/api/tasks")
                .with(csrf())
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isOk());

        // Step 3: 按PENDING状态过滤查询
        mockMvc.perform(get("/api/tasks")
                .with(csrf())
                .header("Authorization", "Bearer " + accessToken)
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records[?(@.status != 'PENDING')]").doesNotExist());
    }

    @Test
    @DisplayName("测试数据一致性 - 创建后立即查询")
    public void shouldMaintainDataConsistency_betweenCreateAndQuery() throws Exception {
        // Step 1: 登录
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("testpass");

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("data")
                .get("accessToken")
                .asText();

        // Step 2: 创建任务
        CreateTaskRequest createRequest = CreateTaskRequestBuilder.aCreateTaskRequest()
                .withTaskCode("E2E-CONSISTENCY")
                .withServiceId("consistency-service")
                .withPriority(8)
                .unitTest()
                .build();

        MvcResult createResult = mockMvc.perform(post("/api/tasks")
                .with(csrf())
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn();

        Long taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data")
                .get("id")
                .asLong();

        // Step 3: 立即查询并验证数据一致性
        mockMvc.perform(get("/api/tasks/{taskId}", taskId)
                .with(csrf())
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskCode").value("E2E-CONSISTENCY"))
                .andExpect(jsonPath("$.data.serviceId").value("consistency-service"))
                .andExpect(jsonPath("$.data.priority").value(8))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }
}
