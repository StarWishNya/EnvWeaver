package com.sentinel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.BusinessException;
import com.sentinel.domain.User;
import com.sentinel.dto.UserInfo;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.UserMapper;
import com.sentinel.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 当前用户只从 SecurityContext 查库，响应里不能出现密码。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService 当前用户")
class AuthServiceCurrentUserTest {

    private static final String PASSWORD_HASH = "$2a$10$should-not-leak";

    @Mock
    private org.springframework.security.authentication.AuthenticationManager authenticationManager;

    @Mock
    private UserMapper userMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private CacheService cacheService;

    @InjectMocks
    private AuthService authService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("按认证用户名返回公开字段，序列化结果不含密码")
    void returnsPublicProfileWithoutPassword() throws Exception {
        authenticate("alice");
        User user = new User();
        user.setId(5L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setRole("ADMIN");
        user.setPassword(PASSWORD_HASH);
        when(userMapper.findByUsername("alice")).thenReturn(user);

        UserInfo info = authService.getCurrentUser();

        assertThat(info.getId()).isEqualTo(5L);
        assertThat(info.getUsername()).isEqualTo("alice");
        assertThat(info.getEmail()).isEqualTo("alice@example.com");
        assertThat(info.getRole()).isEqualTo("ADMIN");
        String json = new ObjectMapper().writeValueAsString(info);
        assertThat(json).doesNotContain(PASSWORD_HASH).doesNotContain("password");
        verify(userMapper).findByUsername("alice");
    }

    @Test
    @DisplayName("没有认证信息时拒绝")
    void rejectsMissingAuthentication() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> authService.getCurrentUser())
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("匿名令牌不算已登录")
    void rejectsAnonymousToken() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThatThrownBy(() -> authService.getCurrentUser())
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("库里没有对应用户时拒绝")
    void rejectsUnknownUser() {
        authenticate("ghost");
        when(userMapper.findByUsername("ghost")).thenReturn(null);

        assertThatThrownBy(() -> authService.getCurrentUser())
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.UNAUTHORIZED.getCode());
    }

    private static void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
