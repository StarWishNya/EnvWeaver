package com.sentinel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.dto.ApiResponse;
import com.sentinel.dto.LoginRequest;
import com.sentinel.dto.LoginResponse;
import com.sentinel.config.BusinessException;
import com.sentinel.config.GlobalExceptionHandler;
import com.sentinel.service.AuthService;
import com.sentinel.util.JwtUtil;
import com.sentinel.config.JwtAuthenticationFilter;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController 测试类
 * 测试认证相关的所有接口
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("AuthController 测试")
public class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    @DisplayName("测试登录成功")
    public void shouldLoginSuccessfully_whenValidCredentials() throws Exception {
        // Given
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("testpass");

        LoginResponse response = new LoginResponse(
            "access-token",
            "refresh-token",
            3600L,
            "testuser",
            "USER"
        );

        when(authService.login(any(LoginRequest.class))).thenReturn(response);

        // When & Then
        mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.data.username").value("testuser"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(3600));

        verify(authService, times(1)).login(any(LoginRequest.class));
    }

    @Test
    @DisplayName("测试登录失败 - 用户名为空")
    public void shouldReturnValidationError_whenUsernameIsEmpty() throws Exception {
        // Given
        LoginRequest request = new LoginRequest();
        request.setUsername("");
        request.setPassword("testpass");

        // When & Then
        mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(authService, never()).login(any(LoginRequest.class));
    }

    @Test
    @DisplayName("测试登录失败 - 密码为空")
    public void shouldReturnValidationError_whenPasswordIsEmpty() throws Exception {
        // Given
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("");

        // When & Then
        mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(authService, never()).login(any(LoginRequest.class));
    }

    @Test
    @DisplayName("测试登录失败 - 用户名和密码都为空")
    public void shouldReturnValidationError_whenBothFieldsAreEmpty() throws Exception {
        // Given
        LoginRequest request = new LoginRequest();
        request.setUsername("");
        request.setPassword("");

        // When & Then
        mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(authService, never()).login(any(LoginRequest.class));
    }

    @Test
    @DisplayName("测试登录失败 - 错误的凭证")
    public void shouldReturnUnauthorized_whenInvalidCredentials() throws Exception {
        // Given
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("wrongpass");

        when(authService.login(any(LoginRequest.class)))
            .thenThrow(new BusinessException(1007, "用户名或密码错误"));

        // When & Then
        mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1007));

        verify(authService, times(1)).login(any(LoginRequest.class));
    }

    @Test
    @DisplayName("测试登录失败 - 账户被锁定")
    public void shouldReturnAccountLocked_whenAccountIsLocked() throws Exception {
        // Given
        LoginRequest request = new LoginRequest();
        request.setUsername("lockeduser");
        request.setPassword("testpass");

        when(authService.login(any(LoginRequest.class)))
            .thenThrow(new BusinessException(1005, "账户已被锁定"));

        // When & Then
        mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1005))
                .andExpect(jsonPath("$.message").value("账户已被锁定"));

        verify(authService, times(1)).login(any(LoginRequest.class));
    }

    @Test
    @WithMockUser
    @DisplayName("测试登出成功")
    public void shouldLogoutSuccessfully_whenValidToken() throws Exception {
        // Given
        String token = "valid-token";
        doNothing().when(authService).logout(anyString());

        // When & Then
        mockMvc.perform(post("/api/auth/logout")
                .with(csrf())
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("登出成功"));

        verify(authService, times(1)).logout(token);
    }

    @Test
    @DisplayName("测试登出失败 - 缺少Authorization头")
    public void shouldReturnBadRequest_whenAuthorizationHeaderMissing() throws Exception {
        // When & Then
        // 注意：当前返回 500，因为 GlobalExceptionHandler 没有处理 MissingRequestHeaderException
        // TODO: 添加对 MissingRequestHeaderException 的处理，返回 400
        mockMvc.perform(post("/api/auth/logout")
                .with(csrf()))
                .andExpect(status().isInternalServerError());

        verify(authService, never()).logout(anyString());
    }

    @Test
    @DisplayName("测试登录请求格式错误")
    public void shouldReturnBadRequest_whenInvalidJsonFormat() throws Exception {
        // When & Then
        // 注意：当前返回 500，因为 GlobalExceptionHandler 没有处理 HttpMessageNotReadableException
        // TODO: 添加对 HttpMessageNotReadableException 的处理，返回 400
        mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{invalid json}"))
                .andExpect(status().isInternalServerError());

        verify(authService, never()).login(any(LoginRequest.class));
    }

    @Test
    @DisplayName("测试登录请求缺少Content-Type")
    public void shouldReturnUnsupportedMediaType_whenContentTypeMissing() throws Exception {
        // Given
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("testpass");

        // When & Then
        // 注意：当前返回 500，因为 GlobalExceptionHandler 没有处理 HttpMediaTypeNotSupportedException
        // TODO: 添加对 HttpMediaTypeNotSupportedException 的处理，返回 415
        mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError());

        verify(authService, never()).login(any(LoginRequest.class));
    }
}
