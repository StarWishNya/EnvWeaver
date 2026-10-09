package com.sentinel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.BusinessException;
import com.sentinel.config.GlobalExceptionHandler;
import com.sentinel.config.JwtAuthenticationFilter;
import com.sentinel.dto.DiagnosisAcceptedResponse;
import com.sentinel.dto.DiagnosisRequest;
import com.sentinel.dto.DiagnosisResultDTO;
import com.sentinel.dto.DiagnosisSubmission;
import com.sentinel.enums.ErrorCode;
import com.sentinel.service.DiagnosisService;
import com.sentinel.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 诊断接口。与 TaskControllerTest 一样关闭过滤器，因此这里断言不到匿名 401。
 * 未登录拒绝由 SecurityConfig 的 anyRequest().authenticated() 覆盖 /api/diagnosis/**。
 */
@WebMvcTest(DiagnosisController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("DiagnosisController 测试")
class DiagnosisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DiagnosisService diagnosisService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    @WithMockUser
    @DisplayName("受理诊断返回 202")
    void shouldAcceptDiagnosis() throws Exception {
        DiagnosisAcceptedResponse accepted = new DiagnosisAcceptedResponse();
        accepted.setDiagnosisId(15L);
        accepted.setStatus("PROCESSING");
        when(diagnosisService.requestDiagnosis(eq(1L), any(DiagnosisRequest.class)))
                .thenReturn(new DiagnosisSubmission(accepted, false));

        DiagnosisRequest request = new DiagnosisRequest();
        request.setForceRefresh(false);
        request.setLog("boom");

        mockMvc.perform(post("/api/diagnosis/{taskId}", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.diagnosisId").value(15))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.fromCache").doesNotExist())
                .andExpect(jsonPath("$.fromCache").value(false));

        verify(diagnosisService).requestDiagnosis(eq(1L), any(DiagnosisRequest.class));
    }

    @Test
    @WithMockUser
    @DisplayName("缓存命中时 202 的外层 fromCache 为 true")
    void shouldMarkWrapperFromCacheWhenCompletedImmediately() throws Exception {
        DiagnosisAcceptedResponse accepted = new DiagnosisAcceptedResponse();
        accepted.setDiagnosisId(42L);
        accepted.setStatus("COMPLETED");
        when(diagnosisService.requestDiagnosis(eq(7L), any(DiagnosisRequest.class)))
                .thenReturn(new DiagnosisSubmission(accepted, true));

        mockMvc.perform(post("/api/diagnosis/{taskId}", 7L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"forceRefresh\":false}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.diagnosisId").value(42))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.fromCache").doesNotExist())
                .andExpect(jsonPath("$.fromCache").value(true));
    }

    @Test
    @WithMockUser
    @DisplayName("查询诊断返回结果字段")
    void shouldReturnDiagnosis() throws Exception {
        DiagnosisResultDTO result = new DiagnosisResultDTO();
        result.setId(42L);
        result.setTaskId(7L);
        result.setErrorType("NETWORK_ERROR");
        result.setSeverity("HIGH");
        result.setSummary("数据库连不上");
        result.setRootCause("mysql:3306 拒绝连接");
        result.setSuggestions(List.of("检查端口", "确认主机名"));
        result.setConfidence(0.5d);
        result.setFromCache(true);
        result.setStatus("COMPLETED");
        result.setProvider("deepseek");
        result.setModel("deepseek-flash");
        result.setCreatedAt(LocalDateTime.of(2026, 10, 9, 8, 0));
        when(diagnosisService.getDiagnosis(7L)).thenReturn(result);

        mockMvc.perform(get("/api/diagnosis/{taskId}", 7L).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.taskId").value(7))
                .andExpect(jsonPath("$.data.errorType").value("NETWORK_ERROR"))
                .andExpect(jsonPath("$.data.severity").value("HIGH"))
                .andExpect(jsonPath("$.data.summary").value("数据库连不上"))
                .andExpect(jsonPath("$.data.rootCause").value("mysql:3306 拒绝连接"))
                .andExpect(jsonPath("$.data.suggestions[0]").value("检查端口"))
                .andExpect(jsonPath("$.data.suggestions[1]").value("确认主机名"))
                .andExpect(jsonPath("$.data.confidence").value(0.5))
                .andExpect(jsonPath("$.data.fromCache").value(true))
                .andExpect(jsonPath("$.fromCache").value(true))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.provider").value("deepseek"))
                .andExpect(jsonPath("$.data.model").value("deepseek-flash"))
                .andExpect(jsonPath("$.data.error").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("没有诊断记录时返回 404 和 4002")
    void shouldReturnNotFoundWhenDiagnosisMissing() throws Exception {
        when(diagnosisService.getDiagnosis(8L))
                .thenThrow(new BusinessException(ErrorCode.DIAGNOSIS_NOT_FOUND));

        mockMvc.perform(get("/api/diagnosis/{taskId}", 8L).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4002))
                .andExpect(jsonPath("$.message").value("诊断结果不存在"));
    }

    @Test
    @WithMockUser
    @DisplayName("任务不存在时返回 404 和 2001")
    void shouldReturnNotFoundWhenTaskMissing() throws Exception {
        when(diagnosisService.requestDiagnosis(eq(9L), any()))
                .thenThrow(new BusinessException(ErrorCode.TASK_NOT_FOUND));

        mockMvc.perform(post("/api/diagnosis/{taskId}", 9L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.message").value("任务不存在"));
    }

    @Test
    @WithMockUser
    @DisplayName("没有可诊断内容时返回 400")
    void shouldReturnBadRequestWhenInputMissing() throws Exception {
        when(diagnosisService.requestDiagnosis(eq(3L), any()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "没有可诊断的内容：请在请求体提供 log，或确保任务有错误信息或容器日志"));

        mockMvc.perform(post("/api/diagnosis/{taskId}", 3L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"log\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5004))
                .andExpect(jsonPath("$.message").value(
                        "没有可诊断的内容：请在请求体提供 log，或确保任务有错误信息或容器日志"));
    }
}
