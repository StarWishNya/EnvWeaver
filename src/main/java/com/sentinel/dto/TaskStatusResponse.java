package com.sentinel.dto;

import lombok.Data;

/**
 * 任务状态快照。
 *
 * @author Sentinel Team
 */
@Data
public class TaskStatusResponse {

    private Long taskId;

    /**
     * PENDING、QUEUED、RUNNING、SUCCESS、FAILED、CANCELLED
     */
    private String status;

    /**
     * 0-100。实体没有进度列时按状态估算。
     */
    private Integer progress;
}
