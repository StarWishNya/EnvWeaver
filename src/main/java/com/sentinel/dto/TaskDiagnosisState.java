package com.sentinel.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个任务的诊断状态，序列化后放在 Redis，TTL 为 cache.diagnosis-ttl。
 *
 * @author Sentinel Team
 */
@Data
public class TaskDiagnosisState {

    private Long diagnosisId;

    private Long taskId;

    private String status;

    private String logHash;

    private boolean fromCache;

    private String errorType;

    private String severity;

    private String summary;

    private String rootCause;

    private List<String> suggestions = new ArrayList<>();

    /**
     * 0 到 1。模型没有给出置信度时为 null，不写成 0。
     */
    private Double confidence;

    private String provider;

    private String model;

    private String error;

    /**
     * ISO-8601 本地时间。避免 Redis 序列化器缺少 JavaTimeModule。
     */
    private String createdAt;
}
