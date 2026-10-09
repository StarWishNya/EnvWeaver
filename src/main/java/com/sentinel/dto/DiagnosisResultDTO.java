package com.sentinel.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务诊断结果。
 * data.fromCache 与响应体外层的 ApiResponse.fromCache 表示同一含义，由控制器写成相同的值。
 *
 * @author Sentinel Team
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiagnosisResultDTO {

    private Long id;

    private Long taskId;

    /**
     * RESOURCE_EXHAUSTED、NETWORK_ERROR、DEPENDENCY_FAILURE、TIMEOUT、CONFIG_ERROR、UNKNOWN。
     */
    private String errorType;

    /**
     * LOW、MEDIUM、HIGH、CRITICAL。
     */
    private String severity;

    private String summary;

    private String rootCause;

    private List<String> suggestions;

    /**
     * 0 到 1。模型没有给出置信度时为 null，不写成 0。
     * 类上的 NON_NULL 会丢掉这个字段，getter 上强制写出 null。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Double confidence;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Double getConfidence() {
        return confidence;
    }

    /**
     * 是否复用了已有诊断，而不是本次新调用模型。
     */
    private Boolean fromCache;

    /**
     * PENDING、PROCESSING、COMPLETED、FAILED。
     */
    private String status;

    private String provider;

    private String model;

    /**
     * status 为 FAILED 时的错误说明。
     */
    private String error;

    private LocalDateTime createdAt;
}
