package com.sentinel.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 容器实体类
 * 
 * @author Sentinel Team
 */
@Data
@TableName("t_container")
public class Container {

    /**
     * 容器记录ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属环境ID
     */
    private Long environmentId;

    /**
     * Docker 容器ID
     */
    private String containerId;

    /**
     * 容器名称
     */
    private String containerName;

    /**
     * 镜像名称(如 mysql:8.0)
     */
    private String image;

    /**
     * 状态: CREATED, RUNNING, STOPPED, ERROR
     */
    private String status;

    /**
     * 端口映射(如 3306:33061)
     */
    private String portMapping;

    /**
     * 健康状态: UNKNOWN, HEALTHY, UNHEALTHY
     */
    private String healthStatus;

    /**
     * 最后日志片段(用于快速诊断)
     */
    private String lastLog;

    /**
     * 启动时间
     */
    private LocalDateTime startedAt;

    /**
     * 停止时间
     */
    private LocalDateTime stoppedAt;

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
