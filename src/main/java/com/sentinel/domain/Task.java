package com.sentinel.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务实体类
 * 
 * @author Sentinel Team
 */
@Data
@TableName("t_task")
public class Task {

    /**
     * 任务ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 任务编码(如 build-1234)
     */
    private String taskCode;

    /**
     * 服务标识(如 user-service)
     */
    private String serviceId;

    /**
     * 测试类型: UNIT_TEST, INTEGRATION_TEST, E2E_TEST
     */
    private String testType;

    /**
     * 依赖列表 JSON: ["mysql:8.0", "redis:7.0"]
     */
    private String dependencies;

    /**
     * 状态: PENDING, QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED
     */
    private String status;

    /**
     * 优先级: 1-10, 数字越大优先级越高
     */
    private Integer priority;

    /**
     * 关联的环境ID
     */
    private Long environmentId;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    private LocalDateTime completedAt;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
