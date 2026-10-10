package com.sentinel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

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
    @Size(max = 64, message = "任务编码长度不能超过64")
    private String taskCode;

    /**
     * 服务标识
     */
    @NotBlank(message = "服务标识不能为空")
    @Size(max = 64, message = "服务标识长度不能超过64")
    private String serviceId;

    /**
     * 测试类型
     */
    @NotBlank(message = "测试类型不能为空")
    @Size(max = 32, message = "测试类型长度不能超过32")
    @Pattern(regexp = "UNIT_TEST|INTEGRATION_TEST|E2E_TEST", message = "测试类型不合法")
    private String testType;

    /**
     * 依赖列表，例如 ["mysql:8.0", "redis:7.0"]
     */
    private List<String> dependencies;

    /**
     * 环境模板名。dependencies 为空时使用模板依赖；两者都有时按种类合并，显式依赖优先。
     */
    @Size(max = 64, message = "模板名长度不能超过64")
    private String template;

    /**
     * 优先级 1-10。不传时按 5 处理。
     */
    @Min(value = 1, message = "优先级必须在1到10之间")
    @Max(value = 10, message = "优先级必须在1到10之间")
    private Integer priority;
}
