package com.sentinel.dto;

import lombok.Data;

/**
 * 依赖健康检查。响应体不包在 ApiResponse 里，也不带异常信息。
 *
 * @author Sentinel Team
 */
@Data
public class ComponentHealth {

    /**
     * 全部为 UP 时为 UP，否则为 DOWN。
     */
    private String status;

    private Components components;

    /**
     * 各依赖状态，取值只有 UP 或 DOWN。
     */
    @Data
    public static class Components {
        private String db;
        private String redis;
        private String docker;
    }
}
