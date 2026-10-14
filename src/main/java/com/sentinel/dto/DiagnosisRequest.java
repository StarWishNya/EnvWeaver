package com.sentinel.dto;

import lombok.Data;

/**
 * 诊断请求体。字段都可选：不传 body 时按任务错误信息和容器日志诊断。
 *
 * @author Sentinel Team
 */
@Data
public class DiagnosisRequest {

    /**
     * 为 true 时忽略相同日志哈希的缓存，重新调用 AI。
     */
    private Boolean forceRefresh;

    /**
     * 直接作为诊断输入。有内容时不再拼接任务错误信息和容器日志。
     */
    private String log;

    /**
     * @return 仅当显式为 true 时强制刷新
     */
    public boolean isForceRefresh() {
        return Boolean.TRUE.equals(forceRefresh);
    }
}
