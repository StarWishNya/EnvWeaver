package com.sentinel.service;

import com.sentinel.mapper.UserMapper;
import com.sentinel.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 登出必须按令牌剩余寿命写入黑名单。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService 登出")
class AuthServiceLogoutTest {

    @Mock
    private AuthenticationManager authenticationManager;

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

    @Test
    @DisplayName("按剩余有效期拉黑原始 Token，不使用固定黑名单 TTL")
    void blacklistsTokenForRemainingLifetime() {
        when(jwtUtil.getRemainingValiditySeconds("raw-token")).thenReturn(5400L);

        authService.logout("raw-token");

        verify(cacheService).addTokenToBlacklist("raw-token", 5400L);
        verify(cacheService, never()).addTokenToBlacklist(anyString());
    }

    @Test
    @DisplayName("已过期令牌不再写入黑名单")
    void skipsBlacklistWhenTokenAlreadyExpired() {
        when(jwtUtil.getRemainingValiditySeconds("expired")).thenReturn(0L);

        authService.logout("expired");

        verifyNoInteractions(cacheService);
    }
}
