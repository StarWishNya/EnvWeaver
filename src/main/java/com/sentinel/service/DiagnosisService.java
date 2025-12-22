package com.sentinel.service;

import com.sentinel.config.AIClientFactory;
import com.sentinel.config.BusinessException;
import com.sentinel.domain.DiagnosisCache;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.DiagnosisCacheMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * AI 诊断服务
 * 
 * @author Sentinel Team
 */
@Slf4j
@Service
public class DiagnosisService {

    private final AIClientFactory aiClientFactory;
    private final DiagnosisCacheMapper diagnosisCacheMapper;
    private final CacheService cacheService;

    public DiagnosisService(AIClientFactory aiClientFactory,
                           DiagnosisCacheMapper diagnosisCacheMapper,
                           CacheService cacheService) {
        this.aiClientFactory = aiClientFactory;
        this.diagnosisCacheMapper = diagnosisCacheMapper;
        this.cacheService = cacheService;
    }

    /**
     * 诊断容器日志
     * 
     * @param containerLog 容器日志
     * @return 诊断结果
     */
    @Transactional
    public String diagnoseLog(String containerLog) {
        // 计算日志哈希
        String logHash = DigestUtils.md5Hex(containerLog);
        
        // 1. 先查 Redis 缓存
        Object cachedResult = cacheService.getDiagnosisCache(logHash);
        if (cachedResult != null) {
            log.info("从 Redis 缓存命中诊断结果: logHash={}", logHash);
            updateCacheHitCount(logHash);
            return (String) cachedResult;
        }
        
        // 2. 查数据库缓存
        DiagnosisCache dbCache = diagnosisCacheMapper.findByLogHash(logHash);
        if (dbCache != null) {
            log.info("从数据库缓存命中诊断结果: logHash={}", logHash);
            String result = buildDiagnosisResult(dbCache);
            
            // 更新命中次数和时间
            dbCache.setHitCount(dbCache.getHitCount() + 1);
            dbCache.setLastHitAt(LocalDateTime.now());
            diagnosisCacheMapper.updateById(dbCache);
            
            // 写入 Redis 缓存
            cacheService.setDiagnosisCache(logHash, result);
            
            return result;
        }
        
        // 3. 调用 AI 服务进行诊断
        log.info("调用 AI 服务进行诊断: logHash={}", logHash);
        String diagnosis = callAIService(containerLog);
        
        // 4. 保存到数据库和 Redis
        saveDiagnosisCache(logHash, containerLog, diagnosis);
        cacheService.setDiagnosisCache(logHash, diagnosis);
        
        return diagnosis;
    }

    /**
     * 调用 AI 服务
     */
    private String callAIService(String containerLog) {
        try {
            AIClient aiClient = aiClientFactory.getAIClient();
            
            if (!aiClient.isAvailable()) {
                throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
            }
            
            String prompt = buildDiagnosisPrompt(containerLog);
            return aiClient.diagnose(prompt);
            
        } catch (Exception e) {
            log.error("AI 诊断失败", e);
            throw new BusinessException(ErrorCode.DIAGNOSIS_FAILED, "AI 诊断失败: " + e.getMessage());
        }
    }

    /**
     * 构建诊断提示词
     */
    private String buildDiagnosisPrompt(String containerLog) {
        return String.format(
            "你是一个专业的容器故障诊断专家。请分析以下容器日志，找出根本原因并提供解决方案。\n\n" +
            "容器日志：\n%s\n\n" +
            "请按以下格式输出：\n" +
            "1. 根本原因：\n" +
            "2. 可能原因：\n" +
            "3. 解决方案：\n",
            containerLog.length() > 2000 ? containerLog.substring(0, 2000) + "..." : containerLog
        );
    }

    /**
     * 保存诊断缓存
     */
    private void saveDiagnosisCache(String logHash, String containerLog, String diagnosis) {
        DiagnosisCache cache = new DiagnosisCache();
        cache.setLogHash(logHash);
        cache.setLogSample(containerLog.length() > 500 ? containerLog.substring(0, 500) : containerLog);
        cache.setRootCause(diagnosis);
        cache.setHitCount(1);
        cache.setLastHitAt(LocalDateTime.now());
        
        diagnosisCacheMapper.insert(cache);
        log.info("诊断结果已保存到数据库: logHash={}", logHash);
    }

    /**
     * 更新缓存命中次数
     */
    private void updateCacheHitCount(String logHash) {
        DiagnosisCache cache = diagnosisCacheMapper.findByLogHash(logHash);
        if (cache != null) {
            cache.setHitCount(cache.getHitCount() + 1);
            cache.setLastHitAt(LocalDateTime.now());
            diagnosisCacheMapper.updateById(cache);
        }
    }

    /**
     * 构建诊断结果
     */
    private String buildDiagnosisResult(DiagnosisCache cache) {
        return cache.getRootCause();
    }
}
