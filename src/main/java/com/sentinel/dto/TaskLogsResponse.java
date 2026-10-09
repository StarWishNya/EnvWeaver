package com.sentinel.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 任务日志。
 *
 * @author Sentinel Team
 */
@Data
public class TaskLogsResponse {

    private String logs;

    /**
     * 任一容器返回的行数达到 tail 时为 true。
     */
    @JsonProperty("hasMore")
    private boolean hasMore;
}
