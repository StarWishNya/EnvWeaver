package com.sentinel.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.domain.Task;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务响应。依赖在库里是 JSON 字符串，这里按规范转成字符串数组。
 *
 * @author Sentinel Team
 */
@Data
public class TaskResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Long id;

    private String taskCode;

    private String serviceId;

    private String testType;

    /**
     * 依赖列表。空列返回空数组。
     */
    private List<String> dependencies;

    private String status;

    /**
     * 进入排队的时间。未排队，或已经出队供给时为空。
     */
    private LocalDateTime queuedAt;

    /**
     * 排队位次，从 1 开始。只有正在排队时有值。
     */
    private Integer queuePosition;

    private Integer priority;

    private Long environmentId;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private String errorMessage;

    public static TaskResponse from(Task task) {
        TaskResponse response = new TaskResponse();
        response.setId(task.getId());
        response.setTaskCode(task.getTaskCode());
        response.setServiceId(task.getServiceId());
        response.setTestType(task.getTestType());
        response.setDependencies(readDependencies(task.getDependencies()));
        response.setStatus(task.getStatus());
        response.setQueuedAt(task.getQueuedAt());
        response.setPriority(task.getPriority());
        response.setEnvironmentId(task.getEnvironmentId());
        response.setCreatedAt(task.getCreatedAt());
        response.setStartedAt(task.getStartedAt());
        response.setCompletedAt(task.getCompletedAt());
        response.setErrorMessage(task.getErrorMessage());
        return response;
    }

    /**
     * 带排队位次。只有 QUEUED 且已经写入 queuedAt 时填写，从 1 开始。
     */
    public static TaskResponse from(Task task, Map<Long, Integer> queuePositions) {
        TaskResponse response = from(task);
        if (queuePositions == null || task == null || task.getId() == null) {
            return response;
        }
        if (!"QUEUED".equals(task.getStatus()) || task.getQueuedAt() == null) {
            return response;
        }
        response.setQueuePosition(queuePositions.get(task.getId()));
        return response;
    }

    /**
     * 无法解析或不是数组时返回空列表，避免把存储格式漏给客户端。
     */
    static List<String> readDependencies(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> items = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                if (item == null || item.isNull()) {
                    continue;
                }
                items.add(item.isTextual() ? item.asText() : item.toString());
            }
            return items;
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }
}
