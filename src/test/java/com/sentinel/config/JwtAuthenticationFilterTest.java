package com.sentinel.config;

import com.sentinel.domain.User;
import com.sentinel.mapper.UserMapper;
import com.sentinel.service.CacheService;
import com.sentinel.util.JwtUtil;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JWT 过滤器：数据库角色、拒绝禁用用户、拒绝刷新令牌。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter 单元测试")
class JwtAuthenticationFilterTest {

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private CacheService cacheService;

    @Mock
    private UserMapper userMapper;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtUtil, cacheService, userMapper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("使用数据库角色认证，不读取令牌中的 role 声明")
    void authenticatesWithDatabaseRole() throws Exception {
        String token = "access-token";
        stubValidAccessToken(token, "alice");
        when(userMapper.findByUsername("alice")).thenReturn(user("alice", "USER", true));

        doFilter(token);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("alice");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
        verify(jwtUtil, never()).getRoleFromToken(anyString());
    }

    @Test
    @DisplayName("用户不存在时不认证")
    void doesNotAuthenticateMissingUser() throws Exception {
        String token = "access-token";
        stubValidAccessToken(token, "ghost");
        when(userMapper.findByUsername("ghost")).thenReturn(null);

        doFilter(token);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("用户已禁用时不认证")
    void doesNotAuthenticateDisabledUser() throws Exception {
        String token = "access-token";
        stubValidAccessToken(token, "alice");
        when(userMapper.findByUsername("alice")).thenReturn(user("alice", "ADMIN", false));

        doFilter(token);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("刷新令牌不能作为访问令牌认证")
    void doesNotAuthenticateRefreshToken() throws Exception {
        String token = "refresh-token";
        when(cacheService.isTokenBlacklisted(token)).thenReturn(false);
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.isTokenExpired(token)).thenReturn(false);
        when(jwtUtil.isAccessToken(token)).thenReturn(false);

        doFilter(token);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userMapper, never()).findByUsername(anyString());
        verify(jwtUtil, never()).getRoleFromToken(anyString());
    }

    @Test
    @DisplayName("黑名单日志只记录短引用，不记录完整令牌")
    void doesNotLogFullBlacklistedToken() throws Exception {
        String token = "header.payload.super-secret-signature-value";
        when(cacheService.isTokenBlacklisted(token)).thenReturn(true);

        Logger logger = (Logger) LoggerFactory.getLogger(JwtAuthenticationFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            doFilter(token);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain(token)
                        .doesNotContain("super-secret-signature-value"))
                .anySatisfy(message -> assertThat(message).contains(JwtAuthenticationFilter.tokenRef(token)));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void stubValidAccessToken(String token, String username) {
        when(cacheService.isTokenBlacklisted(token)).thenReturn(false);
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.isTokenExpired(token)).thenReturn(false);
        when(jwtUtil.isAccessToken(token)).thenReturn(true);
        when(jwtUtil.getUsernameFromToken(token)).thenReturn(username);
    }

    private void doFilter(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    private User user(String username, String role, Boolean enabled) {
        User user = new User();
        user.setUsername(username);
        user.setRole(role);
        user.setEnabled(enabled);
        return user;
    }
}
