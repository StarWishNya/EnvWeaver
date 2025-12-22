package com.sentinel.service;

/**
 * AI 客户端接口
 * 
 * @author Sentinel Team
 */
public interface AIClient {

    /**
     * 发送诊断请求
     * 
     * @param prompt 提示词
     * @return AI 响应
     */
    String diagnose(String prompt);

    /**
     * 检查服务是否可用
     * 
     * @return 是否可用
     */
    boolean isAvailable();
}
