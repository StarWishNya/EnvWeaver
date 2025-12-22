package com.sentinel.service;

import com.sentinel.config.properties.CacheProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存服务
 * 封装常用的缓存操作
 * 
 * @author Sentinel Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheProperties cacheProperties;

    /**
     * 设置缓存
     * 
     * @param key 缓存键
     * @param value 缓存值
     * @param timeout 过期时间
     * @param unit 时间单位
     */
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        try {
            redisTemplate.opsForValue().set(key, value, timeout, unit);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 连接失败，缓存设置失败: key={}", key, e);
        }
    }

    /**
     * 获取缓存
     * 
     * @param key 缓存键
     * @return 缓存值
     */
    public Object get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 连接失败，缓存获取失败: key={}", key, e);
            return null;
        }
    }

    /**
     * 删除缓存
     * 
     * @param key 缓存键
     */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 连接失败，缓存删除失败: key={}", key, e);
        }
    }

    /**
     * 判断缓存是否存在
     * 
     * @param key 缓存键
     * @return 是否存在
     */
    public boolean exists(String key) {
        try {
            Boolean result = redisTemplate.hasKey(key);
            return result != null && result;
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 连接失败，缓存检查失败: key={}", key, e);
            return false;
        }
    }

    /**
     * 设置 AI 诊断结果缓存
     * TTL: 从配置文件读取
     * 
     * @param logHash 日志哈希
     * @param diagnosisResult 诊断结果
     */
    public void setDiagnosisCache(String logHash, Object diagnosisResult) {
        String key = cacheProperties.getDiagnosisKey(logHash);
        set(key, diagnosisResult, cacheProperties.getDiagnosisTtl(), TimeUnit.SECONDS);
    }

    /**
     * 获取 AI 诊断结果缓存
     * 
     * @param logHash 日志哈希
     * @return 诊断结果
     */
    public Object getDiagnosisCache(String logHash) {
        String key = cacheProperties.getDiagnosisKey(logHash);
        return get(key);
    }

    /**
     * 设置系统指标缓存
     * TTL: 从配置文件读取
     * 
     * @param metrics 系统指标
     */
    public void setMetricsCache(Object metrics) {
        String key = cacheProperties.getMetricsKey("system");
        set(key, metrics, cacheProperties.getMetricsTtl(), TimeUnit.SECONDS);
    }

    /**
     * 获取系统指标缓存
     * 
     * @return 系统指标
     */
    public Object getMetricsCache() {
        String key = cacheProperties.getMetricsKey("system");
        return get(key);
    }

    /**
     * 将 Token 加入黑名单
     * TTL: 从配置文件读取
     * 
     * @param token JWT Token
     */
    public void addTokenToBlacklist(String token) {
        String key = cacheProperties.getTokenBlacklistKey(token);
        set(key, true, cacheProperties.getTokenBlacklistTtl(), TimeUnit.SECONDS);
    }

    /**
     * 检查 Token 是否在黑名单中
     * 
     * @param token JWT Token
     * @return 是否在黑名单中
     */
    public boolean isTokenBlacklisted(String token) {
        String key = cacheProperties.getTokenBlacklistKey(token);
        return exists(key);
    }
}
