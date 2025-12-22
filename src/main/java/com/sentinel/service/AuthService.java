package com.sentinel.service;

import com.sentinel.config.BusinessException;
import com.sentinel.domain.User;
import com.sentinel.dto.LoginRequest;
import com.sentinel.dto.LoginResponse;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.UserMapper;
import com.sentinel.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 认证服务
 * 
 * @author Sentinel Team
 */
@Slf4j
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final LoginAttemptService loginAttemptService;
    private final PasswordEncoder passwordEncoder;

    @Value("${jwt.expiration}")
    private Long jwtExpiration;

    public AuthService(AuthenticationManager authenticationManager,
                      UserMapper userMapper,
                      JwtUtil jwtUtil,
                      LoginAttemptService loginAttemptService,
                      PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.loginAttemptService = loginAttemptService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 用户登录
     * 
     * @param request 登录请求
     * @return 登录响应
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername();
        
        // 检查账户是否被锁定
        if (loginAttemptService.isBlocked(username)) {
            log.warn("账户已被锁定: {}", username);
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        
        try {
            // 认证
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, request.getPassword())
            );
            
            // 认证成功，清除失败记录
            loginAttemptService.loginSucceeded(username);
            
            // 获取用户信息
            User user = userMapper.findByUsername(username);
            if (user == null) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            
            // 更新最后登录时间
            user.setLastLoginAt(LocalDateTime.now());
            userMapper.updateById(user);
            
            // 生成 Token
            String accessToken = jwtUtil.generateToken(username, user.getRole());
            String refreshToken = jwtUtil.generateRefreshToken(username);
            
            log.info("用户登录成功: username={}, role={}", username, user.getRole());
            
            return new LoginResponse(
                accessToken,
                refreshToken,
                jwtExpiration / 1000, // 转换为秒
                username,
                user.getRole()
            );
            
        } catch (BadCredentialsException e) {
            // 认证失败，记录失败次数
            loginAttemptService.loginFailed(username);
            int remainingAttempts = loginAttemptService.getRemainingAttempts(username);
            
            log.warn("用户登录失败: username={}, 剩余尝试次数={}", username, remainingAttempts);
            
            if (remainingAttempts == 0) {
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
            }
            
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS, 
                "用户名或密码错误，剩余尝试次数: " + remainingAttempts);
        }
    }

    /**
     * 用户登出
     * 
     * @param token JWT Token
     */
    public void logout(String token) {
        // 将 Token 加入黑名单
        // 注意：这里需要从 CacheService 注入，暂时省略
        log.info("用户登出");
    }
}
