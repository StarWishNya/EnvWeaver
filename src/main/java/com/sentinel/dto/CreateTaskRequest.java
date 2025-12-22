package com.sentinel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建任务请求 DTO
 * 
 * @author Sentinel Team
 */
@Data
public class CreateTaskRequest {

    /**
     * 任务编码
     */
    @NotBlank(message = "任务编码不能为空")
    private String taskCode;

    /**
     * 服务标识
     */
    @NotBlank(message = "服务标识不能为空")
    private String serviceId;

    /**
     * 测试类型
     */
    @NotBlank(message = "测试类型不能为空")
    private String testType;

    /**
     * 依赖列表 JSON
     */
    private String dependencies;

    /**
     * 优先级
     */
    @NotNull(message = "优先级不能为空")
    private Integer priority;
}
