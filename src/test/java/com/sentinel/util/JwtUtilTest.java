package com.sentinel.util;

import com.sentinel.config.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * JwtUtil 单元测试
 * 测试 JWT Token 的生成、验证、解析等功能
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JwtUtil 单元测试")
public class JwtUtilTest {

    @Mock
    private JwtProperties jwtProperties;

    @InjectMocks
    private JwtUtil jwtUtil;

    private static final String TEST_SECRET = "test-secret-key-for-jwt-token-generation-minimum-32-characters";
    private static final Long TEST_EXPIRATION = 3600000L; // 1小时
    private static final Long TEST_REFRESH_EXPIRATION = 86400000L; // 24小时

    @BeforeEach
    public void setUp() {
        when(jwtProperties.getSecret()).thenReturn(TEST_SECRET);
        when(jwtProperties.getExpiration()).thenReturn(TEST_EXPIRATION);
        when(jwtProperties.getRefreshExpiration()).thenReturn(TEST_REFRESH_EXPIRATION);
        when(jwtProperties.getIssuer()).thenReturn("sentinel");
        when(jwtProperties.getAudience()).thenReturn("sentinel-users");
    }

    @Test
    @DisplayName("测试生成访问Token成功")
    public void shouldGenerateToken_whenValidCredentials() {
        // Given
        String username = "testuser";
        String role = "USER";

        // When
        String token = jwtUtil.generateToken(username, role);

        // Then
        assertThat(token).isNotNull();
        assertThat(token).isNotEmpty();
        assertThat(token.split("\\.")).hasSize(3); // JWT格式：header.payload.signature
    }

    @Test
    @DisplayName("测试生成刷新Token成功")
    public void shouldGenerateRefreshToken_whenValidUsername() {
        // Given
        String username = "testuser";

        // When
        String refreshToken = jwtUtil.generateRefreshToken(username);

        // Then
        assertThat(refreshToken).isNotNull();
        assertThat(refreshToken).isNotEmpty();
        assertThat(refreshToken.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("测试验证有效Token")
    public void shouldValidateToken_whenTokenIsValid() {
        // Given
        String username = "testuser";
        String role = "USER";
        String token = jwtUtil.generateToken(username, role);

        // When
        boolean isValid = jwtUtil.validateToken(token);

        // Then
        assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("测试验证无效Token - 格式错误")
    public void shouldReturnFalse_whenTokenFormatIsInvalid() {
        // Given
        String invalidToken = "invalid.token.format";

        // When
        boolean isValid = jwtUtil.validateToken(invalidToken);

        // Then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("测试验证无效Token - 空字符串")
    public void shouldReturnFalse_whenTokenIsEmpty() {
        // Given
        String emptyToken = "";

        // When
        boolean isValid = jwtUtil.validateToken(emptyToken);

        // Then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("测试验证无效Token - null")
    public void shouldReturnFalse_whenTokenIsNull() {
        // Given
        String nullToken = null;

        // When
        boolean isValid = jwtUtil.validateToken(nullToken);

        // Then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("测试从Token中获取用户名")
    public void shouldExtractUsername_whenTokenIsValid() {
        // Given
        String username = "testuser";
        String role = "USER";
        String token = jwtUtil.generateToken(username, role);

        // When
        String extractedUsername = jwtUtil.getUsernameFromToken(token);

        // Then
        assertThat(extractedUsername).isEqualTo(username);
    }

    @Test
    @DisplayName("测试从Token中获取角色")
    public void shouldExtractRole_whenTokenIsValid() {
        // Given
        String username = "testuser";
        String role = "ADMIN";
        String token = jwtUtil.generateToken(username, role);

        // When
        String extractedRole = jwtUtil.getRoleFromToken(token);

        // Then
        assertThat(extractedRole).isEqualTo(role);
    }

    @Test
    @DisplayName("测试Token未过期")
    public void shouldReturnFalse_whenTokenIsNotExpired() {
        // Given
        String username = "testuser";
        String role = "USER";
        String token = jwtUtil.generateToken(username, role);

        // When
        boolean isExpired = jwtUtil.isTokenExpired(token);

        // Then
        assertThat(isExpired).isFalse();
    }

    @Test
    @DisplayName("测试Token已过期")
    public void shouldReturnTrue_whenTokenIsExpired() {
        // Given - 创建一个已过期的Token
        when(jwtProperties.getExpiration()).thenReturn(-1000L); // 负数表示已过期
        String username = "testuser";
        String role = "USER";
        String token = jwtUtil.generateToken(username, role);

        // 恢复正常的过期时间配置
        when(jwtProperties.getExpiration()).thenReturn(TEST_EXPIRATION);

        // When
        boolean isExpired = jwtUtil.isTokenExpired(token);

        // Then
        assertThat(isExpired).isTrue();
    }

    @Test
    @DisplayName("测试不同用户生成不同Token")
    public void shouldGenerateDifferentTokens_forDifferentUsers() {
        // Given
        String user1 = "user1";
        String user2 = "user2";
        String role = "USER";

        // When
        String token1 = jwtUtil.generateToken(user1, role);
        String token2 = jwtUtil.generateToken(user2, role);

        // Then
        assertThat(token1).isNotEqualTo(token2);
    }

    @Test
    @DisplayName("测试不同角色生成不同Token")
    public void shouldGenerateDifferentTokens_forDifferentRoles() {
        // Given
        String username = "testuser";
        String role1 = "USER";
        String role2 = "ADMIN";

        // When
        String token1 = jwtUtil.generateToken(username, role1);
        String token2 = jwtUtil.generateToken(username, role2);

        // Then
        assertThat(token1).isNotEqualTo(token2);
    }

    @Test
    @DisplayName("测试访问Token和刷新Token不同")
    public void shouldGenerateDifferentTokens_forAccessAndRefresh() {
        // Given
        String username = "testuser";
        String role = "USER";

        // When
        String accessToken = jwtUtil.generateToken(username, role);
        String refreshToken = jwtUtil.generateRefreshToken(username);

        // Then
        assertThat(accessToken).isNotEqualTo(refreshToken);
    }

    @Test
    @DisplayName("测试Token包含正确的声明")
    public void shouldContainCorrectClaims_whenTokenGenerated() {
        // Given
        String username = "testuser";
        String role = "ADMIN";
        String token = jwtUtil.generateToken(username, role);

        // When
        String extractedUsername = jwtUtil.getUsernameFromToken(token);
        String extractedRole = jwtUtil.getRoleFromToken(token);

        // Then
        assertThat(extractedUsername).isEqualTo(username);
        assertThat(extractedRole).isEqualTo(role);
    }

    @Test
    @DisplayName("测试Token签名验证 - 被篡改的Token")
    public void shouldReturnFalse_whenTokenIsTampered() {
        // Given
        String username = "testuser";
        String role = "USER";
        String token = jwtUtil.generateToken(username, role);
        
        // 篡改Token（修改最后一个字符）
        String tamperedToken = token.substring(0, token.length() - 1) + "X";

        // When
        boolean isValid = jwtUtil.validateToken(tamperedToken);

        // Then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("测试使用错误密钥验证Token")
    public void shouldReturnFalse_whenValidateWithWrongSecret() {
        // Given
        String username = "testuser";
        String role = "USER";
        String token = jwtUtil.generateToken(username, role);

        // 修改密钥
        when(jwtProperties.getSecret()).thenReturn("wrong-secret-key-for-jwt-token-generation-minimum-32-chars");

        // When
        boolean isValid = jwtUtil.validateToken(token);

        // Then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("测试Token生成时间戳")
    public void shouldHaveIssuedAtTimestamp_whenTokenGenerated() {
        // Given
        String username = "testuser";
        String role = "USER";
        long beforeGeneration = System.currentTimeMillis();

        // When
        String token = jwtUtil.generateToken(username, role);
        
        // 手动解析Token以验证时间戳
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        
        long afterGeneration = System.currentTimeMillis();

        // Then
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getIssuedAt().getTime()).isBetween(beforeGeneration - 1000, afterGeneration + 1000);
    }

    @Test
    @DisplayName("测试Token过期时间设置正确")
    public void shouldHaveCorrectExpiration_whenTokenGenerated() {
        // Given
        String username = "testuser";
        String role = "USER";
        long beforeGeneration = System.currentTimeMillis();

        // When
        String token = jwtUtil.generateToken(username, role);
        
        // 手动解析Token以验证过期时间
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        
        long expectedExpiration = beforeGeneration + TEST_EXPIRATION;

        // Then
        assertThat(claims.getExpiration()).isNotNull();
        assertThat(claims.getExpiration().getTime()).isBetween(expectedExpiration - 1000, expectedExpiration + 1000);
    }

    @Test
    @DisplayName("测试刷新Token过期时间长于访问Token")
    public void shouldHaveLongerExpiration_forRefreshToken() {
        // Given
        String username = "testuser";

        // When
        String accessToken = jwtUtil.generateToken(username, "USER");
        String refreshToken = jwtUtil.generateRefreshToken(username);
        
        // 手动解析Token以比较过期时间
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        
        Claims accessClaims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(accessToken)
                .getPayload();
        
        Claims refreshClaims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(refreshToken)
                .getPayload();

        // Then
        assertThat(refreshClaims.getExpiration().getTime())
            .isGreaterThan(accessClaims.getExpiration().getTime());
    }
}
