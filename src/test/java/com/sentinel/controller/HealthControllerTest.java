package com.sentinel.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import com.sentinel.util.JwtUtil;
import com.sentinel.config.JwtAuthenticationFilter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HealthController 测试类
 * 测试健康检查接口
 */
@WebMvcTest(HealthController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("HealthController 测试")
public class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    @DisplayName("测试健康检查接口返回正确格式")
    public void shouldReturnHealthStatus_whenHealthEndpointCalled() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.application").value("Sentinel"))
                .andExpect(jsonPath("$.version").value("1.0.0-SNAPSHOT"));
    }

    @Test
    @DisplayName("测试健康检查接口无需认证")
    public void shouldAccessHealthEndpoint_withoutAuthentication() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("测试健康检查接口返回所有必需字段")
    public void shouldReturnAllRequiredFields() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.application").exists())
                .andExpect(jsonPath("$.version").exists());
    }
}
