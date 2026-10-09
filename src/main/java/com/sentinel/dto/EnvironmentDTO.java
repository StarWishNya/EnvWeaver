package com.sentinel.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 环境信息。端口映射与资源使用由表中的 JSON 字符串解析而来。
 *
 * @author Sentinel Team
 */
@Data
public class EnvironmentDTO {

    private Long id;

    private String envCode;

    /**
     * CREATING、READY、BUSY、RELEASING、IDLE、ERROR。
     */
    private String status;

    private Long taskId;

    private String composeContent;

    private String accessUrl;

    /**
     * 端口映射，例如 mysql -> 33061。无法解析时为 null。
     */
    private Map<String, Integer> portMappings;

    /**
     * 资源使用，cpu 为数字，memory 为整数。无法解析时为 null。
     */
    private Map<String, Object> resourceUsage;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
}
