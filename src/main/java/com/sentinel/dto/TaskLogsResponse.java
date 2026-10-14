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

    /**
     * 下一轮只拉新行时原样传回的 since。无法可靠续传时为 null。
     * 有新行时是已返回日志里最晚时间戳加 1 纳秒；没有新行时等于本次 since。
     */
    private String nextSince;
}
