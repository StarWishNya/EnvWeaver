package com.sentinel.util;

import com.sentinel.config.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类
 * 
 * @author Sentinel Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    /** 载荷中的令牌类型声明 */
    public static final String CLAIM_TOKEN_TYPE = "typ";

    /** 访问令牌 */
    public static final String TOKEN_TYPE_ACCESS = "access";

    /** 刷新令牌 */
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    private final JwtProperties jwtProperties;

    /**
     * 生成 JWT Token
     * 
     * @param username 用户名
     * @param role 角色
     * @return JWT Token
     */
    public String generateToken(String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("username", username);
        claims.put("role", role);
        claims.put(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS);
        return createToken(claims, username, jwtProperties.getExpiration());
    }

    /**
     * 生成刷新 Token
     * 
     * @param username 用户名
     * @return 刷新 Token
     */
    public String generateRefreshToken(String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("username", username);
        claims.put(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH);
        return createToken(claims, username, jwtProperties.getRefreshExpiration());
    }

    /**
     * 创建 Token
     * 
     * @param claims 声明
     * @param subject 主题
     * @param expiration 过期时间（毫秒）
     * @return Token
     */
    private String createToken(Map<String, Object> claims, String subject, Long expiration) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    /**
     * 验证 Token
     * 
     * @param token JWT Token
     * @return 是否有效
     */
    public boolean validateToken(String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
            Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            log.error("Token 验证失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 Token 中获取用户名
     * 
     * @param token JWT Token
     * @return 用户名
     */
    public String getUsernameFromToken(String token) {
        return getClaimsFromToken(token).getSubject();
    }

    /**
     * 从 Token 中获取角色
     * 
     * @param token JWT Token
     * @return 角色
     */
    public String getRoleFromToken(String token) {
        return (String) getClaimsFromToken(token).get("role");
    }

    /**
     * 从 Token 中获取声明
     * 
     * @param token JWT Token
     * @return 声明
     */
    private Claims getClaimsFromToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 判断 Token 是否过期
     * 
     * @param token JWT Token
     * @return 是否过期
     */
    public boolean isTokenExpired(String token) {
        try {
            Date expiration = getClaimsFromToken(token).getExpiration();
            return expiration.before(new Date());
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 读取令牌类型（access / refresh）。解析失败时返回 null。
     *
     * @param token JWT Token
     * @return 令牌类型
     */
    public String getTokenType(String token) {
        try {
            Object type = getClaimsFromToken(token).get(CLAIM_TOKEN_TYPE);
            return type instanceof String ? (String) type : null;
        } catch (Exception e) {
            log.debug("无法解析 Token 类型: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 是否为访问令牌。刷新令牌、缺少类型声明或签名无效时返回 false。
     *
     * @param token JWT Token
     * @return 是否为访问令牌
     */
    public boolean isAccessToken(String token) {
        return TOKEN_TYPE_ACCESS.equals(getTokenType(token));
    }

    /**
     * Token 剩余有效期（秒，向上取整）。
     * 已过期、没有过期时间或无法解析时返回 0，避免用固定 TTL 把黑名单提前清掉。
     *
     * @param token JWT Token
     * @return 剩余秒数
     */
    public long getRemainingValiditySeconds(String token) {
        try {
            Date expiration = getClaimsFromToken(token).getExpiration();
            if (expiration == null) {
                return 0L;
            }
            long remainingMillis = expiration.getTime() - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                return 0L;
            }
            return (remainingMillis + 999L) / 1000L;
        } catch (Exception e) {
            log.warn("无法计算 Token 剩余有效期: {}", e.getMessage());
            return 0L;
        }
    }
}
