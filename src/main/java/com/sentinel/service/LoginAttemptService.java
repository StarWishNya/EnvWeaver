package com.sentinel.service;

import com.sentinel.config.properties.SecurityProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 登录尝试服务
 * 实现登录失败锁定机制
 * 
 * @author Sentinel Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final SecurityProperties securityProperties;

    /**
     * 记录登录失败
     * 
     * @param username 用户名
     */
    public void loginFailed(String username) {
        String key = "login:attempts:" + username;
        Integer attempts = (Integer) redisTemplate.opsForValue().get(key);
        
        if (attempts == null) {
            attempts = 0;
        }
        
        attempts++;
        long lockTimeMinutes = securityProperties.getLoginAttempt().getLockTimeMinutes();
        redisTemplate.opsForValue().set(key, attempts, lockTimeMinutes, TimeUnit.MINUTES);
        
        int maxAttempts = securityProperties.getLoginAttempt().getMaxAttempts();
        if (attempts >= maxAttempts) {
            log.warn("用户 {} 登录失败次数达到 {} 次，账户已锁定 {} 分钟", username, maxAttempts, lockTimeMinutes);
        }
    }

    /**
     * 登录成功，清除失败记录
     * 
     * @param username 用户名
     */
    public void loginSucceeded(String username) {
        String key = "login:attempts:" + username;
        redisTemplate.delete(key);
    }

    /**
     * 检查账户是否被锁定
     * 
     * @param username 用户名
     * @return 是否被锁定
     */
    public boolean isBlocked(String username) {
        String key = "login:attempts:" + username;
        Integer attempts = (Integer) redisTemplate.opsForValue().get(key);
        int maxAttempts = securityProperties.getLoginAttempt().getMaxAttempts();
        return attempts != null && attempts >= maxAttempts;
    }

    /**
     * 获取剩余尝试次数
     * 
     * @param username 用户名
     * @return 剩余尝试次数
     */
    public int getRemainingAttempts(String username) {
        String key = "login:attempts:" + username;
        Integer attempts = (Integer) redisTemplate.opsForValue().get(key);
        int maxAttempts = securityProperties.getLoginAttempt().getMaxAttempts();
        if (attempts == null) {
            return maxAttempts;
        }
        return Math.max(0, maxAttempts - attempts);
    }
}
