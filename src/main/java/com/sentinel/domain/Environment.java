package com.sentinel.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 环境实体类
 * 
 * @author Sentinel Team
 */
@Data
@TableName("t_environment")
public class Environment {

    /**
     * 环境ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 环境编码(如 env-abc123)
     */
    private String envCode;

    /**
     * 关联的任务ID
     */
    private Long taskId;

    /**
     * 状态: CREATING, READY, BUSY, RELEASING, IDLE, ERROR
     */
    private String status;

    /**
     * 生成的 docker-compose.yml 内容
     */
    private String composeContent;

    /**
     * 访问地址
     */
    private String accessUrl;

    /**
     * 端口映射 JSON: {"mysql": 33061, "redis": 63791}
     */
    private String portMappings;

    /**
     * 资源使用 JSON: {"cpu": 0.5, "memory": 512}
     */
    private String resourceUsage;

    /**
     * 过期时间(用于自动回收)
     */
    private LocalDateTime expiresAt;

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
