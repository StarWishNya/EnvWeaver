package com.sentinel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.BusinessException;
import com.sentinel.domain.Container;
import com.sentinel.domain.Environment;
import com.sentinel.dto.EnvironmentDTO;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.ContainerMapper;
import com.sentinel.mapper.EnvironmentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 环境查询与释放。
 *
 * @author Sentinel Team
 */
@Slf4j
@Service
public class EnvironmentService {

    /**
     * 列表最多返回的条数，避免一次查出全表。
     */
    public static final int MAX_LIST_SIZE = 500;

    private static final String STATUS_RELEASING = "RELEASING";
    private static final String STATUS_IDLE = "IDLE";
    private static final String STATUS_ERROR = "ERROR";
    private static final String CONTAINER_STOPPED = "STOPPED";

    private static final Set<String> ENVIRONMENT_STATUSES = Set.of(
            "CREATING", "READY", "BUSY", "RELEASING", "IDLE", "ERROR");

    private final EnvironmentMapper environmentMapper;
    private final ContainerMapper containerMapper;
    private final DockerService dockerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EnvironmentService(EnvironmentMapper environmentMapper,
                              ContainerMapper containerMapper,
                              DockerService dockerService) {
        this.environmentMapper = environmentMapper;
        this.containerMapper = containerMapper;
        this.dockerService = dockerService;
    }

    /**
     * 按 id 倒序查询环境。status 为空时不过滤；非法状态返回 400。
     */
    public List<EnvironmentDTO> listEnvironments(String status) {
        String normalized = normalizeStatus(status);
        List<Environment> rows = environmentMapper.selectRecent(normalized, MAX_LIST_SIZE);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<EnvironmentDTO> result = new ArrayList<>(rows.size());
        for (Environment row : rows) {
            result.add(toDto(row));
        }
        return result;
    }

    /**
     * 查询单个环境。不存在时返回 404。
     */
    public EnvironmentDTO getEnvironment(Long envId) {
        Environment environment = environmentMapper.selectById(envId);
        if (environment == null) {
            throw new BusinessException(ErrorCode.ENVIRONMENT_NOT_FOUND);
        }
        return toDto(environment);
    }

    /**
     * 释放环境：先标记 RELEASING，停掉并删除容器。
     * 成功的容器记录改为 STOPPED；停止或删除失败（容器已不存在除外）的记录改为 ERROR，不记为 STOPPED。
     * 任一容器失败时环境改为 ERROR，并抛出 DOCKER_ERROR（HTTP 503），消息只包含失败数量。
     * ERROR 可再次释放。已是 IDLE 或 RELEASING 时拒绝，HTTP 400（OPERATION_NOT_ALLOWED）。
     * Docker 守护进程整体不可用时把环境标为 ERROR 并抛出原 DOCKER_ERROR。
     * 不加外层事务：失败状态需要提交，不能随异常回滚。
     */
    public void releaseEnvironment(Long envId) {
        Environment environment = environmentMapper.selectById(envId);
        if (environment == null) {
            throw new BusinessException(ErrorCode.ENVIRONMENT_NOT_FOUND);
        }
        if (STATUS_IDLE.equals(environment.getStatus()) || STATUS_RELEASING.equals(environment.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_NOT_ALLOWED, "环境已空闲或正在释放");
        }

        environment.setStatus(STATUS_RELEASING);
        environmentMapper.updateById(environment);
        try {
            releaseContainers(environment);
            environment.setStatus(STATUS_IDLE);
            environmentMapper.updateById(environment);
            log.info("环境已释放: envId={}", environment.getId());
        } catch (BusinessException ex) {
            markError(environment);
            throw ex;
        } catch (RuntimeException ex) {
            markError(environment);
            throw ex;
        }
    }

    private void releaseContainers(Environment environment) {
        List<Container> containers = containerMapper.findByEnvironmentId(environment.getId());
        if (containers == null) {
            containers = List.of();
        }
        if (needsDocker(containers)) {
            dockerService.assertDockerAvailable();
        }
        int failed = 0;
        for (Container container : containers) {
            if (container == null) {
                continue;
            }
            String containerId = container.getContainerId();
            if (containerId != null && !containerId.isBlank()) {
                try {
                    dockerService.stopAndRemoveContainer(containerId);
                } catch (Exception ex) {
                    failed++;
                    log.warn("释放环境时容器停止或删除失败，容器记为 ERROR: envId={}, containerRecordId={}",
                            environment.getId(), container.getId(), ex);
                    container.setStatus(STATUS_ERROR);
                    containerMapper.updateById(container);
                    continue;
                }
            }
            container.setStatus(CONTAINER_STOPPED);
            container.setStoppedAt(LocalDateTime.now());
            containerMapper.updateById(container);
        }
        if (failed > 0) {
            throw new BusinessException(ErrorCode.DOCKER_ERROR, failed + " 个容器释放失败");
        }
    }

    private void markError(Environment environment) {
        try {
            environment.setStatus(STATUS_ERROR);
            environmentMapper.updateById(environment);
            log.error("环境释放失败，已标记为 ERROR: envId={}", environment.getId());
        } catch (RuntimeException updateError) {
            log.error("环境释放失败且无法标记 ERROR: envId={}", environment.getId(), updateError);
        }
    }

    private boolean needsDocker(List<Container> containers) {
        for (Container container : containers) {
            if (container == null) {
                continue;
            }
            String containerId = container.getContainerId();
            if (containerId != null && !containerId.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim();
        if (!ENVIRONMENT_STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "环境状态无效");
        }
        return normalized;
    }

    private EnvironmentDTO toDto(Environment environment) {
        EnvironmentDTO dto = new EnvironmentDTO();
        dto.setId(environment.getId());
        dto.setEnvCode(environment.getEnvCode());
        dto.setStatus(environment.getStatus());
        dto.setTaskId(environment.getTaskId());
        dto.setComposeContent(environment.getComposeContent());
        dto.setAccessUrl(environment.getAccessUrl());
        dto.setPortMappings(parsePortMappings(environment.getId(), environment.getPortMappings()));
        dto.setResourceUsage(parseResourceUsage(environment.getId(), environment.getResourceUsage()));
        dto.setExpiresAt(environment.getExpiresAt());
        dto.setCreatedAt(environment.getCreatedAt());
        return dto;
    }

    private Map<String, Integer> parsePortMappings(Long envId, String json) {
        JsonNode node = readObjectNode(envId, "portMappings", json);
        if (node == null) {
            return null;
        }
        Map<String, Integer> mappings = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            Integer port = toInteger(entry.getValue());
            if (port == null) {
                log.warn("环境端口映射值不是整数，已忽略: envId={}, key={}", envId, entry.getKey());
                return null;
            }
            mappings.put(entry.getKey(), port);
        }
        return mappings;
    }

    private Map<String, Object> parseResourceUsage(Long envId, String json) {
        JsonNode node = readObjectNode(envId, "resourceUsage", json);
        if (node == null) {
            return null;
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            if (value != null && value.isNull()) {
                usage.put(entry.getKey(), null);
                continue;
            }
            if (value == null || !value.isNumber()) {
                log.warn("环境资源使用值不是数字，已忽略: envId={}, key={}", envId, entry.getKey());
                return null;
            }
            if (value.isIntegralNumber()) {
                // 分开赋值，避免三元表达式把 int 提升成 long。
                if (value.canConvertToInt()) {
                    usage.put(entry.getKey(), value.intValue());
                } else {
                    usage.put(entry.getKey(), value.longValue());
                }
            } else {
                double number = value.doubleValue();
                if (!Double.isFinite(number)) {
                    log.warn("环境资源使用值不是有限数字，已忽略: envId={}, key={}", envId, entry.getKey());
                    return null;
                }
                usage.put(entry.getKey(), number);
            }
        }
        return usage;
    }

    /**
     * 空字符串与 JSON null 视为没有该字段。非法 JSON 或非对象记警告并返回 null，不向上抛。
     */
    private JsonNode readObjectNode(Long envId, String field, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        final JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            log.warn("环境字段 JSON 无法解析，已忽略: envId={}, field={}", envId, field);
            return null;
        }
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            log.warn("环境字段不是 JSON 对象，已忽略: envId={}, field={}", envId, field);
            return null;
        }
        return node;
    }

    private Integer toInteger(JsonNode value) {
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            if (!value.canConvertToInt()) {
                return null;
            }
            return value.intValue();
        }
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)) {
            return null;
        }
        if (number > Integer.MAX_VALUE || number < Integer.MIN_VALUE) {
            return null;
        }
        return (int) number;
    }
}
