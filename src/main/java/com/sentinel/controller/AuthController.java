package com.sentinel.controller;

import com.sentinel.dto.ApiResponse;
import com.sentinel.dto.LoginRequest;
import com.sentinel.dto.LoginResponse;
import com.sentinel.dto.UserInfo;
import com.sentinel.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 * 
 * @author Sentinel Team
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证管理", description = "用户登录、登出等认证相关接口")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "使用用户名和密码登录系统")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("用户登录请求: username={}", request.getUsername());
        LoginResponse response = authService.login(request);
        return ApiResponse.success(response);
    }

    /**
     * 用户登出
     */
    @PostMapping("/logout")
    @Operation(summary = "用户登出", description = "登出系统，Token 将被加入黑名单")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String authorization) {
        String token = extractRawToken(authorization);
        authService.logout(token);
        return ApiResponse.success("登出成功", null);
    }

    /**
     * 当前登录用户。未认证由安全链返回 401；这里只返回公开字段。
     */
    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息", description = "返回已认证用户的 id、用户名、邮箱和角色，不包含密码")
    public ApiResponse<UserInfo> me() {
        log.info("获取当前用户信息");
        return ApiResponse.success(authService.getCurrentUser());
    }

    /**
     * 去掉 Authorization 头中的 "Bearer " 前缀，得到原始 Token。
     * 没有该前缀时原样返回，避免 substring 截断令牌。
     */
    private String extractRawToken(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        return authorization == null ? null : authorization.trim();
    }
}
