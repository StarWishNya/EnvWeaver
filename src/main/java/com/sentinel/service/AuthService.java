package com.sentinel.service;

import com.sentinel.config.BusinessException;
import com.sentinel.domain.User;
import com.sentinel.dto.LoginRequest;
import com.sentinel.dto.LoginResponse;
import com.sentinel.dto.UserInfo;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.UserMapper;
import com.sentinel.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
    private final CacheService cacheService;

    @Value("${jwt.expiration}")
    private Long jwtExpiration;

    public AuthService(AuthenticationManager authenticationManager,
                      UserMapper userMapper,
                      JwtUtil jwtUtil,
                      LoginAttemptService loginAttemptService,
                      PasswordEncoder passwordEncoder,
                      CacheService cacheService) {
        this.authenticationManager = authenticationManager;
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.loginAttemptService = loginAttemptService;
        this.passwordEncoder = passwordEncoder;
        this.cacheService = cacheService;
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
        if (!StringUtils.hasText(token)) {
            log.warn("登出失败：Token 为空");
            return;
        }
        // 按令牌剩余寿命拉黑，不能用 cache.token-blacklist-ttl（通常短于访问令牌寿命）
        long ttlSeconds = jwtUtil.getRemainingValiditySeconds(token);
        if (ttlSeconds <= 0) {
            log.info("用户登出：Token 已过期或无效，无需加入黑名单");
            return;
        }
        cacheService.addTokenToBlacklist(token, ttlSeconds);
        log.info("用户登出，Token 已按剩余有效期 {} 秒加入黑名单", ttlSeconds);
    }

    /**
     * 当前认证用户的公开资料。从 SecurityContext 取用户名，再查库。
     * 不返回密码。
     */
    public UserInfo getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isAuthenticatedUser(authentication)) {
            log.warn("获取当前用户失败：未认证");
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String username = authentication.getName();
        User user = userMapper.findByUsername(username);
        if (user == null) {
            log.warn("获取当前用户失败：用户不存在: {}", username);
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        log.info("获取当前用户信息: username={}", username);
        UserInfo info = new UserInfo();
        info.setId(user.getId());
        info.setUsername(user.getUsername());
        info.setEmail(user.getEmail());
        info.setRole(user.getRole());
        return info;
    }

    /**
     * 匿名令牌的 isAuthenticated 也是 true，不能只看这个标志。
     */
    private static boolean isAuthenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }
        String name = authentication.getName();
        return StringUtils.hasText(name) && !"anonymousUser".equals(name);
    }
}
