package com.sentinel.integration;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.ai.AIRequest;
import com.sentinel.ai.AIResponse;
import com.sentinel.ai.AIRouter;
import com.sentinel.config.BusinessException;
import com.sentinel.config.DiagnosisAsyncConfig;
import com.sentinel.config.EmbeddedRedisConfig;
import com.sentinel.config.LifecycleAsyncConfig;
import com.sentinel.config.properties.CacheProperties;
import com.sentinel.domain.Container;
import com.sentinel.domain.DiagnosisCache;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Task;
import com.sentinel.dto.DiagnosisRequest;
import com.sentinel.dto.LoginRequest;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.ContainerMapper;
import com.sentinel.mapper.DiagnosisCacheMapper;
import com.sentinel.mapper.EnvironmentMapper;
import com.sentinel.mapper.TaskMapper;
import com.sentinel.service.CacheService;
import com.sentinel.service.DockerService;
import com.sentinel.service.provision.ContainerLauncher;
import com.sentinel.service.provision.TcpProbe;
import com.sentinel.testutil.CreateTaskRequestBuilder;
import com.sentinel.testutil.TestUtils;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录、供给、诊断、取消的进程内流程。
 * MySQL 用 test profile（与 DatabaseIntegrationTest 相同），Redis 用 EmbeddedRedisConfig（与 RedisIntegrationTest 相同）。
 * ContainerLauncher、TcpProbe、DockerService、AIRouter 都是测试替身，不访问 Docker 守护进程和 AI 接口。
 * 不用类级事务：供给监听器在事务提交后才运行，回滚事务不会触发它。
 *
 * 相对旧用例的改写：
 * testuser/testpass 不是种子用户，登录改为 user/user123。
 * 创建后不再一直停在 PENDING；自动供给会把它变成 RUNNING，取消会释放环境。
 * 分页不再要求全表正好 5 行；共享库里已有其他任务。
 * 按状态过滤改到供给完成之后，避免和 PENDING→RUNNING 抢跑。
 * 创建后立即核对字段并到成功流程里。另补一条供给失败：任务 FAILED、环境 ERROR、自动诊断落库。
 */
@SpringBootTest(properties = {
        "sentinel.lifecycle.auto-provision=true",
        "sentinel.lifecycle.auto-diagnose=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedRedisConfig.class)
@DisplayName("端到端集成测试")
public class EndToEndIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String USERNAME = "user";
    private static final String PASSWORD = "user123";
    private static final String DIAGNOSIS_JSON = "{\"errorType\":\"DEPENDENCY_FAILURE\",\"severity\":\"HIGH\","
            + "\"summary\":\"数据库连不上\",\"rootCause\":\"MySQL 未就绪\","
            + "\"suggestions\":[\"检查端口\",\"重试连接\"],\"confidence\":0.86}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private EnvironmentMapper environmentMapper;

    @Autowired
    private ContainerMapper containerMapper;

    @Autowired
    private DiagnosisCacheMapper diagnosisCacheMapper;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private CacheProperties cacheProperties;

    @Autowired
    @Qualifier(LifecycleAsyncConfig.LIFECYCLE_EXECUTOR)
    private ThreadPoolTaskExecutor lifecycleExecutor;

    @Autowired
    @Qualifier(DiagnosisAsyncConfig.DIAGNOSIS_EXECUTOR)
    private ThreadPoolTaskExecutor diagnosisExecutor;

    @MockBean
    private ContainerLauncher containerLauncher;

    @MockBean
    private TcpProbe tcpProbe;

    @MockBean
    private DockerService dockerService;

    @MockBean
    private AIRouter aiRouter;

    private final String prefix = "E2E" + TestUtils.uniqueToken();
    private final List<Long> taskIds = new ArrayList<>();
    private final List<String> tokens = new ArrayList<>();
    private final AtomicInteger containerSequence = new AtomicInteger();

    @BeforeEach
    void stubExternalServices() {
        lenient().doNothing().when(containerLauncher).ensureImage(anyString());
        lenient().doNothing().when(containerLauncher).remove(anyString());
        lenient().when(containerLauncher.inspect(anyString()))
                .thenReturn(new ContainerLauncher.Snapshot(true, null));
        lenient().when(containerLauncher.recentLogs(anyString(), anyInt())).thenReturn("");
        lenient().when(containerLauncher.start(any())).thenAnswer(invocation -> {
            String containerId = "c" + prefix + containerSequence.incrementAndGet();
            return new ContainerLauncher.StartedContainer(containerId, false);
        });
        lenient().when(tcpProbe.accepts(anyString(), anyInt())).thenReturn(true);

        lenient().when(dockerService.getContainersByEnvironmentId(anyLong())).thenAnswer(invocation ->
                containerMapper.findByEnvironmentId(invocation.getArgument(0)));
        lenient().when(dockerService.getContainerLogs(anyString(), anyInt())).thenReturn("mocked-container-log");
        lenient().when(dockerService.getContainerLogs(anyString(), anyInt(), nullable(Instant.class)))
                .thenReturn("mocked-container-log");
        lenient().doNothing().when(dockerService).assertDockerAvailable();
        lenient().doNothing().when(dockerService).stopAndRemoveContainer(anyString());

        lenient().when(aiRouter.complete(any(AIRequest.class)))
                .thenReturn(new AIResponse("mock-provider", "mock-model", DIAGNOSIS_JSON));
        lenient().when(aiRouter.status()).thenReturn(List.of());
    }

    @AfterEach
    void removeRowsCreatedByThisTest() throws Exception {
        try {
            awaitIdle();
        } finally {
            deleteRows();
            deleteRedisKeys();
        }
    }

    @Test
    @DisplayName("登录后供给环境、完成诊断、取消并登出")
    public void shouldCompleteFullWorkflow_fromLoginToTaskManagement() throws Exception {
        String token = login();
        String taskCode = prefix + "-flow";
        long taskId = createTask(token, taskCode, "consistency-service", 8);

        JsonNode running = awaitTask(token, taskId, "RUNNING");
        assertThat(running.path("taskCode").asText()).isEqualTo(taskCode);
        assertThat(running.path("serviceId").asText()).isEqualTo("consistency-service");
        assertThat(running.path("priority").asInt()).isEqualTo(8);
        assertThat(running.path("dependencies").get(0).asText()).isEqualTo("mysql:8.0");
        assertThat(running.path("startedAt").isNull()).isFalse();
        long environmentId = running.path("environmentId").asLong();

        Task stored = taskMapper.selectById(taskId);
        assertThat(stored.getStatus()).isEqualTo("RUNNING");
        assertThat(stored.getPriority()).isEqualTo(8);
        assertThat(stored.getServiceId()).isEqualTo("consistency-service");
        assertThat(stored.getEnvironmentId()).isEqualTo(environmentId);
        assertThat(stored.getStartedAt()).isNotNull();

        Environment environment = environmentMapper.selectById(environmentId);
        assertThat(environment.getStatus()).isEqualTo("BUSY");
        assertThat(environment.getTaskId()).isEqualTo(taskId);
        assertThat(environment.getExpiresAt()).isNotNull();
        assertThat(environment.getComposeContent()).contains("mysql:8.0");
        assertThat(environment.getPortMappings()).contains("mysql");
        assertThat(environment.getAccessUrl()).isEqualTo("localhost");

        List<Container> containers = containerMapper.findByEnvironmentId(environmentId);
        assertThat(containers).hasSize(1);
        Container container = containers.get(0);
        assertThat(container.getImage()).isEqualTo("mysql:8.0");
        assertThat(container.getStatus()).isEqualTo("RUNNING");
        assertThat(container.getHealthStatus()).isEqualTo("HEALTHY");
        assertThat(container.getContainerId()).startsWith("c" + prefix);
        assertThat(container.getPortMapping()).contains("3306");

        mockMvc.perform(get("/api/tasks/{taskId}/status", taskId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.progress").value(50));

        mockMvc.perform(get("/api/tasks/{taskId}/logs", taskId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logs").value(org.hamcrest.Matchers.containsString("mocked-container-log")));

        String log = "connection refused " + prefix;
        MvcResult accepted = mockMvc.perform(post("/api/diagnosis/{taskId}", taskId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(diagnosisRequest(log))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.fromCache").value(false))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.diagnosisId").isNumber())
                .andReturn();
        long acceptedId = json(accepted).path("data").path("diagnosisId").asLong();

        JsonNode diagnosis = awaitDiagnosis(token, taskId);
        assertDiagnosis(diagnosis, taskId, false);
        assertThat(diagnosis.path("data").path("id").asLong()).isEqualTo(acceptedId);

        DiagnosisCache cacheRow = diagnosisCacheMapper.findByLogHash(DigestUtils.md5Hex(log));
        assertThat(cacheRow).isNotNull();
        assertThat(cacheRow.getId()).isEqualTo(acceptedId);
        assertThat(cacheRow.getRootCause()).isEqualTo("MySQL 未就绪");
        assertThat(cacheRow.getHitCount()).isEqualTo(1);
        assertThat(cacheRow.getSolutions()).contains("检查端口").contains("重试连接");
        assertThat(cacheRow.getPossibleReasons()).contains("DEPENDENCY_FAILURE").contains("mock-provider");
        assertThat(cacheRow.getLogSample()).contains(prefix);

        mockMvc.perform(post("/api/diagnosis/{taskId}", taskId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(diagnosisRequest(log))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fromCache").value(true))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.diagnosisId").value(acceptedId));

        DiagnosisCache hit = diagnosisCacheMapper.selectById(acceptedId);
        assertThat(hit.getHitCount()).isEqualTo(2);
        JsonNode cachedView = awaitDiagnosis(token, taskId);
        assertDiagnosis(cachedView, taskId, true);
        verify(aiRouter, times(1)).complete(any(AIRequest.class));
        verify(containerLauncher).ensureImage("mysql:8.0");
        verify(containerLauncher, atLeastOnce()).start(any());
        verify(tcpProbe, atLeastOnce()).accepts(eq("localhost"), anyInt());
        verify(dockerService, never()).createAndStartContainer(anyLong(), anyString(), anyString(), anyInt(), anyInt());

        mockMvc.perform(delete("/api/tasks/{taskId}", taskId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("任务已取消"));

        JsonNode cancelled = awaitTask(token, taskId, "CANCELLED");
        assertThat(cancelled.path("completedAt").isNull()).isFalse();
        mockMvc.perform(get("/api/tasks/{taskId}/status", taskId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progress").value(100));

        Environment released = awaitEnvironment(environmentId, "IDLE");
        assertThat(released.getStatus()).isEqualTo("IDLE");
        Container stopped = containerMapper.selectById(container.getId());
        assertThat(stopped.getStatus()).isEqualTo("STOPPED");
        assertThat(stopped.getStoppedAt()).isNotNull();
        verify(dockerService).stopAndRemoveContainer(container.getContainerId());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("登出成功"));
        assertThat(cacheService.isTokenBlacklisted(token)).isTrue();

        mockMvc.perform(get("/api/tasks/{taskId}", taskId).header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("测试未认证用户访问受保护资源")
    public void shouldReturnUnauthorized_whenAccessProtectedResourceWithoutToken() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("未授权，请先登录"));
    }

    @Test
    @DisplayName("测试使用无效Token访问受保护资源")
    public void shouldReturnUnauthorized_whenAccessWithInvalidToken() throws Exception {
        mockMvc.perform(get("/api/tasks").header("Authorization", "Bearer invalid.token.here"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("未授权，请先登录"));
    }

    @Test
    @DisplayName("测试健康检查接口无需认证")
    public void shouldAccessHealthEndpoint_withoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.application").value("Sentinel"))
                .andExpect(jsonPath("$.components.db").value("UP"))
                .andExpect(jsonPath("$.components.redis").value("UP"));
    }

    @Test
    @DisplayName("测试创建多个任务并分页查询")
    public void shouldCreateMultipleTasksAndQueryWithPagination() throws Exception {
        String token = login();
        Long before = taskMapper.selectCount(null);
        List<Long> created = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            created.add(createTask(token, prefix + "-page-" + i, "page-service-" + i, 5));
        }
        for (Long taskId : created) {
            awaitTask(token, taskId, "RUNNING");
        }

        Long after = taskMapper.selectCount(null);
        assertThat(after).isEqualTo(before + created.size());
        for (Long taskId : created) {
            Task task = taskMapper.selectById(taskId);
            assertThat(task.getStatus()).isEqualTo("RUNNING");
            assertThat(task.getEnvironmentId()).isNotNull();
            assertThat(environmentMapper.selectById(task.getEnvironmentId()).getStatus()).isEqualTo("BUSY");
        }

        int size = 2;
        long totalPages = (after + size - 1) / size;
        mockMvc.perform(get("/api/tasks")
                        .header("Authorization", bearer(token))
                        .param("page", "0")
                        .param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(size))
                .andExpect(jsonPath("$.data.content.length()").value(size))
                .andExpect(jsonPath("$.data.totalElements").value(after.intValue()))
                .andExpect(jsonPath("$.data.totalPages").value((int) totalPages));
    }

    @Test
    @DisplayName("测试按状态过滤任务")
    public void shouldFilterTasksByStatus() throws Exception {
        String token = login();
        String taskCode = prefix + "-filter";
        long taskId = createTask(token, taskCode, "filter-service", 5);
        awaitTask(token, taskId, "RUNNING");

        JsonNode listed = findTask(token, "RUNNING", taskCode);
        assertThat(listed.path("status").asText()).isEqualTo("RUNNING");
        assertThat(findTaskOrNull(token, "PENDING", taskCode)).isNull();

        mockMvc.perform(delete("/api/tasks/{taskId}", taskId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("任务已取消"));
        awaitTask(token, taskId, "CANCELLED");

        JsonNode cancelled = findTask(token, "CANCELLED", taskCode);
        assertThat(cancelled.path("id").asLong()).isEqualTo(taskId);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(findTaskOrNull(token, "RUNNING", taskCode)).isNull();
        assertThat(taskMapper.selectById(taskId).getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("供给失败后任务失败并自动写出诊断")
    public void shouldFailTaskAndPersistDiagnosis_whenProvisionFails() throws Exception {
        when(containerLauncher.start(any())).thenThrow(
                new BusinessException(ErrorCode.DOCKER_ERROR, "mock start failed " + prefix));

        String token = login();
        long taskId = createTask(token, prefix + "-fail", "fail-service", 4);
        JsonNode failedView = awaitTask(token, taskId, "FAILED");
        assertThat(failedView.path("errorMessage").asText()).contains("mock start failed " + prefix);
        long environmentId = failedView.path("environmentId").asLong();

        Task failed = taskMapper.selectById(taskId);
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getCompletedAt()).isNotNull();
        assertThat(failed.getEnvironmentId()).isEqualTo(environmentId);
        assertThat(failed.getErrorMessage()).contains(prefix);

        Environment environment = awaitEnvironment(environmentId, "ERROR");
        assertThat(environment.getStatus()).isEqualTo("ERROR");
        assertThat(environment.getTaskId()).isEqualTo(taskId);
        assertThat(environment.getPortMappings()).isNull();
        assertThat(environment.getExpiresAt()).isNotNull();
        assertThat(environment.getExpiresAt()).isBefore(LocalDateTime.now().plusMinutes(45));
        assertThat(containerMapper.findByEnvironmentId(environmentId)).isEmpty();

        JsonNode diagnosis = awaitDiagnosis(token, taskId);
        assertDiagnosis(diagnosis, taskId, false);
        String hash = DigestUtils.md5Hex(failed.getErrorMessage().trim());
        DiagnosisCache cacheRow = diagnosisCacheMapper.findByLogHash(hash);
        assertThat(cacheRow).isNotNull();
        assertThat(cacheRow.getId()).isEqualTo(diagnosis.path("data").path("id").asLong());
        assertThat(cacheRow.getRootCause()).isEqualTo("MySQL 未就绪");
        assertThat(cacheRow.getLogSample()).contains(prefix);
        assertThat(cacheRow.getPossibleReasons()).contains("HIGH").contains("mock-model");
        verify(aiRouter, times(1)).complete(any(AIRequest.class));
        verify(containerLauncher).ensureImage("mysql:8.0");
        verify(containerLauncher, never()).remove(anyString());
        verify(dockerService, never()).stopAndRemoveContainer(anyString());
        verify(tcpProbe, never()).accepts(anyString(), anyInt());
    }

    private String login() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername(USERNAME);
        request.setPassword(PASSWORD);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.username").value(USERNAME))
                .andReturn();
        String token = json(result).path("data").path("accessToken").asText();
        tokens.add(token);
        return token;
    }

    private long createTask(String token, String taskCode, String serviceId, int priority) throws Exception {
        var request = CreateTaskRequestBuilder.aCreateTaskRequest()
                .withTaskCode(taskCode)
                .withServiceId(serviceId)
                .withPriority(priority)
                .withDependencies(List.of("mysql:8.0"))
                .unitTest()
                .build();
        MvcResult result = mockMvc.perform(post("/api/tasks")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskCode").value(taskCode))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.priority").value(priority))
                .andExpect(jsonPath("$.data.serviceId").value(serviceId))
                .andReturn();
        long taskId = json(result).path("data").path("id").asLong();
        taskIds.add(taskId);
        return taskId;
    }

    private static DiagnosisRequest diagnosisRequest(String log) {
        DiagnosisRequest request = new DiagnosisRequest();
        request.setLog(log);
        return request;
    }

    private static void assertDiagnosis(JsonNode body, long taskId, boolean fromCache) {
        JsonNode data = body.path("data");
        assertThat(body.path("fromCache").asBoolean()).isEqualTo(fromCache);
        assertThat(data.path("fromCache").asBoolean()).isEqualTo(fromCache);
        assertThat(data.path("taskId").asLong()).isEqualTo(taskId);
        assertThat(data.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(data.path("errorType").asText()).isEqualTo("DEPENDENCY_FAILURE");
        assertThat(data.path("severity").asText()).isEqualTo("HIGH");
        assertThat(data.path("summary").asText()).isEqualTo("数据库连不上");
        assertThat(data.path("rootCause").asText()).isEqualTo("MySQL 未就绪");
        assertThat(data.path("provider").asText()).isEqualTo("mock-provider");
        assertThat(data.path("model").asText()).isEqualTo("mock-model");
        assertThat(data.path("confidence").asDouble()).isCloseTo(0.86, within(0.0001));
        assertThat(data.path("suggestions")).hasSize(2);
        assertThat(data.path("suggestions").get(0).asText()).isEqualTo("检查端口");
        assertThat(data.path("suggestions").get(1).asText()).isEqualTo("重试连接");
    }

    private JsonNode awaitTask(String token, long taskId, String expected) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        String last = null;
        JsonNode data = null;
        while (System.nanoTime() < deadline) {
            MvcResult result = mockMvc.perform(get("/api/tasks/{taskId}", taskId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andReturn();
            data = json(result).path("data");
            last = data.path("status").asText();
            if (expected.equals(last)) {
                return data;
            }
            if ("FAILED".equals(last) && !"FAILED".equals(expected)) {
                throw new AssertionError("任务失败: " + data.path("errorMessage").asText());
            }
            if ("CANCELLED".equals(last) && !"CANCELLED".equals(expected)) {
                throw new AssertionError("任务已取消: " + taskId);
            }
            Thread.sleep(40);
        }
        throw new AssertionError("等待状态 " + expected + " 超时，最后状态 " + last + " body=" + data);
    }

    private JsonNode awaitDiagnosis(String token, long taskId) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        String last = null;
        while (System.nanoTime() < deadline) {
            MvcResult result = mockMvc.perform(get("/api/diagnosis/{taskId}", taskId)
                            .header("Authorization", bearer(token)))
                    .andReturn();
            last = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (result.getResponse().getStatus() == 200) {
                JsonNode body = objectMapper.readTree(last);
                String diagnosisStatus = body.path("data").path("status").asText();
                if ("COMPLETED".equals(diagnosisStatus)) {
                    return body;
                }
                if ("FAILED".equals(diagnosisStatus)) {
                    throw new AssertionError("诊断失败: " + body.path("data").path("error").asText());
                }
            }
            Thread.sleep(40);
        }
        throw new AssertionError("等待诊断完成超时，最后响应 " + last);
    }

    private Environment awaitEnvironment(long environmentId, String expected) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        String last = null;
        while (System.nanoTime() < deadline) {
            Environment environment = environmentMapper.selectById(environmentId);
            if (environment != null) {
                last = environment.getStatus();
                if (expected.equals(last)) {
                    return environment;
                }
            }
            Thread.sleep(40);
        }
        throw new AssertionError("等待环境 " + environmentId + " 变为 " + expected + " 超时，最后状态 " + last);
    }

    private JsonNode findTask(String token, String status, String taskCode) throws Exception {
        JsonNode found = findTaskOrNull(token, status, taskCode);
        assertThat(found).as("状态 %s 的列表应包含 %s", status, taskCode).isNotNull();
        return found;
    }

    private JsonNode findTaskOrNull(String token, String statusFilter, String taskCode) throws Exception {
        int page = 0;
        while (page < 30) {
            MvcResult result = mockMvc.perform(get("/api/tasks")
                            .header("Authorization", bearer(token))
                            .param("page", String.valueOf(page))
                            .param("size", "100")
                            .param("status", statusFilter))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode data = json(result).path("data");
            for (JsonNode item : data.path("content")) {
                assertThat(item.path("status").asText()).isEqualTo(statusFilter);
                if (taskCode.equals(item.path("taskCode").asText())) {
                    return item;
                }
            }
            int totalPages = data.path("totalPages").asInt();
            if (page + 1 >= totalPages) {
                return null;
            }
            page++;
        }
        return null;
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (idle(lifecycleExecutor) && idle(diagnosisExecutor)) {
                Thread.sleep(30);
                if (idle(lifecycleExecutor) && idle(diagnosisExecutor)) {
                    return;
                }
            }
            Thread.sleep(30);
        }
    }

    private static boolean idle(ThreadPoolTaskExecutor executor) {
        if (executor == null) {
            return true;
        }
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        return pool.getActiveCount() == 0 && pool.getQueue().isEmpty();
    }

    private void deleteRows() {
        if (taskMapper == null) {
            return;
        }
        Set<Long> ids = new LinkedHashSet<>(taskIds);
        List<Task> tasks = taskMapper.selectList(new QueryWrapper<Task>().likeRight("task_code", prefix));
        for (Task task : tasks) {
            if (task.getId() != null) {
                ids.add(task.getId());
            }
        }
        Set<Long> environmentIds = new LinkedHashSet<>();
        for (Task task : tasks) {
            if (task.getEnvironmentId() != null) {
                environmentIds.add(task.getEnvironmentId());
            }
        }
        if (!ids.isEmpty()) {
            List<Environment> environments = environmentMapper.selectList(
                    new QueryWrapper<Environment>().in("task_id", ids));
            for (Environment environment : environments) {
                if (environment.getId() != null) {
                    environmentIds.add(environment.getId());
                }
            }
        }
        if (!environmentIds.isEmpty()) {
            containerMapper.delete(new QueryWrapper<Container>().in("environment_id", environmentIds));
            environmentMapper.delete(new QueryWrapper<Environment>().in("id", environmentIds));
        }
        if (!ids.isEmpty()) {
            taskMapper.delete(new QueryWrapper<Task>().in("id", ids));
        }
        if (diagnosisCacheMapper != null) {
            diagnosisCacheMapper.delete(new QueryWrapper<DiagnosisCache>().like("log_sample", prefix));
        }
    }

    private void deleteRedisKeys() {
        if (cacheService == null || cacheProperties == null) {
            return;
        }
        for (Long taskId : taskIds) {
            cacheService.delete(cacheProperties.getDiagnosisKey("task:" + taskId));
        }
        for (String token : tokens) {
            cacheService.delete(cacheProperties.getTokenBlacklistKey(token));
        }
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
