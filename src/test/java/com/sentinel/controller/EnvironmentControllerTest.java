package com.sentinel.controller;

import com.sentinel.config.BusinessException;
import com.sentinel.config.GlobalExceptionHandler;
import com.sentinel.config.JwtAuthenticationFilter;
import com.sentinel.dto.EnvironmentDTO;
import com.sentinel.enums.ErrorCode;
import com.sentinel.service.EnvironmentService;
import com.sentinel.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 环境接口。与 DiagnosisControllerTest 一样关闭过滤器。
 */
@WebMvcTest(EnvironmentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("EnvironmentController 测试")
class EnvironmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EnvironmentService environmentService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    @WithMockUser
    @DisplayName("环境列表返回 Environment 字段")
    void shouldListEnvironments() throws Exception {
        when(environmentService.listEnvironments(null)).thenReturn(List.of(sampleEnvironment()));

        mockMvc.perform(get("/api/environments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].envCode").value("env-abc123"))
                .andExpect(jsonPath("$.data[0].status").value("READY"))
                .andExpect(jsonPath("$.data[0].taskId").value(9))
                .andExpect(jsonPath("$.data[0].composeContent").value("services: {}\n"))
                .andExpect(jsonPath("$.data[0].accessUrl").value("http://127.0.0.1:18080"))
                .andExpect(jsonPath("$.data[0].portMappings.mysql").value(33061))
                .andExpect(jsonPath("$.data[0].portMappings.redis").value(63791))
                .andExpect(jsonPath("$.data[0].resourceUsage.cpu").value(0.5))
                .andExpect(jsonPath("$.data[0].resourceUsage.memory").value(512))
                .andExpect(jsonPath("$.data[0].expiresAt").value("2026-10-09T12:00:00"))
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-10-09T08:00:00"))
                .andExpect(jsonPath("$.data[0].updatedAt").doesNotExist());
    }

    @Test
    @WithMockUser
    @DisplayName("按状态过滤环境")
    void shouldFilterEnvironmentsByStatus() throws Exception {
        when(environmentService.listEnvironments("BUSY")).thenReturn(List.of());

        mockMvc.perform(get("/api/environments").param("status", "BUSY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(environmentService).listEnvironments("BUSY");
    }

    @Test
    @WithMockUser
    @DisplayName("按 RETAINED 过滤返回 200")
    void shouldFilterRetainedEnvironments() throws Exception {
        EnvironmentDTO retained = sampleEnvironment();
        retained.setStatus("RETAINED");
        when(environmentService.listEnvironments("RETAINED")).thenReturn(List.of(retained));

        mockMvc.perform(get("/api/environments").param("status", "RETAINED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].status").value("RETAINED"));

        verify(environmentService).listEnvironments("RETAINED");
    }

    @Test
    @WithMockUser
    @DisplayName("非法状态返回 400")
    void shouldReturnBadRequestWhenStatusInvalid() throws Exception {
        when(environmentService.listEnvironments("running"))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "环境状态无效"));

        mockMvc.perform(get("/api/environments").param("status", "running"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5004))
                .andExpect(jsonPath("$.message").value("环境状态无效"));
    }

    @Test
    @WithMockUser
    @DisplayName("环境详情返回 200")
    void shouldReturnEnvironment() throws Exception {
        when(environmentService.getEnvironment(1L)).thenReturn(sampleEnvironment());

        mockMvc.perform(get("/api/environments/{envId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.envCode").value("env-abc123"))
                .andExpect(jsonPath("$.data.portMappings.mysql").value(33061))
                .andExpect(jsonPath("$.data.resourceUsage.memory").value(512));
    }

    @Test
    @WithMockUser
    @DisplayName("环境不存在时返回 404 和 3001")
    void shouldReturnNotFoundWhenEnvironmentMissing() throws Exception {
        when(environmentService.getEnvironment(8L))
                .thenThrow(new BusinessException(ErrorCode.ENVIRONMENT_NOT_FOUND));

        mockMvc.perform(get("/api/environments/{envId}", 8L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001))
                .andExpect(jsonPath("$.message").value("环境不存在"));
    }

    @Test
    @WithMockUser
    @DisplayName("释放环境返回 200")
    void shouldReleaseEnvironment() throws Exception {
        mockMvc.perform(post("/api/environments/{envId}/release", 1L).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"));

        verify(environmentService).releaseEnvironment(1L);
    }

    @Test
    @WithMockUser
    @DisplayName("释放不存在的环境返回 404")
    void shouldReturnNotFoundWhenReleaseTargetMissing() throws Exception {
        doThrow(new BusinessException(ErrorCode.ENVIRONMENT_NOT_FOUND))
                .when(environmentService).releaseEnvironment(8L);

        mockMvc.perform(post("/api/environments/{envId}/release", 8L).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001))
                .andExpect(jsonPath("$.message").value("环境不存在"));
    }

    @Test
    @WithMockUser
    @DisplayName("重复释放返回 400")
    void shouldReturnConflictWhenReleaseNotAllowed() throws Exception {
        doThrow(new BusinessException(ErrorCode.OPERATION_NOT_ALLOWED, "环境已空闲或正在释放"))
                .when(environmentService).releaseEnvironment(1L);

        mockMvc.perform(post("/api/environments/{envId}/release", 1L).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5007))
                .andExpect(jsonPath("$.message").value("环境已空闲或正在释放"));
    }

    @Test
    @WithMockUser
    @DisplayName("Docker 不可用时释放返回 503")
    void shouldReturnServiceUnavailableWhenDockerDown() throws Exception {
        doThrow(new BusinessException(ErrorCode.DOCKER_ERROR, "Docker 服务不可用"))
                .when(environmentService).releaseEnvironment(1L);

        mockMvc.perform(post("/api/environments/{envId}/release", 1L).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(5003))
                .andExpect(jsonPath("$.message").value("Docker 服务不可用"));
    }

    @Test
    @WithMockUser
    @DisplayName("部分容器释放失败返回 503 和失败数量")
    void shouldReturnServiceUnavailableWhenContainersFail() throws Exception {
        doThrow(new BusinessException(ErrorCode.DOCKER_ERROR, "1 个容器释放失败"))
                .when(environmentService).releaseEnvironment(1L);

        mockMvc.perform(post("/api/environments/{envId}/release", 1L).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(5003))
                .andExpect(jsonPath("$.message").value("1 个容器释放失败"));
    }

    private EnvironmentDTO sampleEnvironment() {
        EnvironmentDTO dto = new EnvironmentDTO();
        dto.setId(1L);
        dto.setEnvCode("env-abc123");
        dto.setStatus("READY");
        dto.setTaskId(9L);
        dto.setComposeContent("services: {}\n");
        dto.setAccessUrl("http://127.0.0.1:18080");
        Map<String, Integer> ports = new LinkedHashMap<>();
        ports.put("mysql", 33061);
        ports.put("redis", 63791);
        dto.setPortMappings(ports);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("cpu", 0.5d);
        usage.put("memory", 512);
        dto.setResourceUsage(usage);
        dto.setExpiresAt(LocalDateTime.of(2026, 10, 9, 12, 0));
        dto.setCreatedAt(LocalDateTime.of(2026, 10, 9, 8, 0));
        return dto;
    }
}
