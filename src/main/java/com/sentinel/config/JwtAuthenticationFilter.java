package com.sentinel.config;

import com.sentinel.domain.User;
import com.sentinel.mapper.UserMapper;
import com.sentinel.service.CacheService;
import com.sentinel.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;

/**
 * JWT 认证过滤器
 * 
 * @author Sentinel Team
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CacheService cacheService;
    private final UserMapper userMapper;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, CacheService cacheService, UserMapper userMapper) {
        this.jwtUtil = jwtUtil;
        this.cacheService = cacheService;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        try {
            String token = getTokenFromRequest(request);

            if (StringUtils.hasText(token)) {
                // 检查 Token 是否在黑名单中（Redis 故障时失败关闭，同样视为已拉黑）
                if (cacheService.isTokenBlacklisted(token)) {
                    log.warn("Token 已被加入黑名单: {}", tokenRef(token));
                } else if (jwtUtil.validateToken(token)
                        && !jwtUtil.isTokenExpired(token)
                        && jwtUtil.isAccessToken(token)) {
                    // 只接受未过期的访问令牌；刷新令牌不能当作访问令牌使用
                    authenticateFromDatabase(request, token);
                }
            }
        } catch (Exception e) {
            log.error("JWT 认证失败", e);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 用数据库中的用户和角色建立认证。用户不存在、已禁用或角色为空时不写入 SecurityContext。
     * 令牌里的 role 声明不可信。
     */
    private void authenticateFromDatabase(HttpServletRequest request, String token) {
        String username = jwtUtil.getUsernameFromToken(token);
        User user = userMapper.findByUsername(username);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || !StringUtils.hasText(user.getRole())) {
            log.warn("拒绝认证，用户不存在、已禁用或角色为空: {}", username);
            return;
        }
        String role = user.getRole();

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username,
                null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        log.debug("JWT 认证成功: username={}, role={}", username, role);
    }

    /**
     * 日志里只留 JWT 头部前缀和 SHA-256 前 8 位，避免把签名打进日志。
     * 短令牌不截前缀，防止几乎整段原文出现在日志中。
     */
    static String tokenRef(String token) {
        String digest = sha256Prefix(token);
        if (token.length() <= 16) {
            return "#" + digest;
        }
        return token.substring(0, 8) + "…#" + digest;
    }

    private static String sha256Prefix(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    /**
     * 从请求中获取 Token
     *
     * @param request HTTP 请求
     * @return Token
     */
    private String getTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
