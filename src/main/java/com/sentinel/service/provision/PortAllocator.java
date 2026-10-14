package com.sentinel.service.provision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.EnvironmentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从配置的端口区间分配主机端口。
 * 同一进程内的分配串行执行，并记住尚未写入数据库的端口，避免并发供给撞车。
 */
@Slf4j
@Component
public class PortAllocator {

    private final Object lock = new Object();
    private final Set<Integer> reserved = new HashSet<>();
    private final EnvironmentMapper environmentMapper;
    private final ProvisioningProperties properties;
    private final PortProbe portProbe;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PortAllocator(EnvironmentMapper environmentMapper,
                         ProvisioningProperties properties,
                         PortProbe portProbe) {
        this.environmentMapper = environmentMapper;
        this.properties = properties;
        this.portProbe = portProbe;
    }

    /**
     * 分配 count 个互不相同的空闲端口。调用方结束供给后必须 release。
     */
    public List<Integer> allocate(int count) {
        if (count < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "端口分配数量无效");
        }
        if (count == 0) {
            return List.of();
        }
        int[] bounds = portBounds();
        synchronized (lock) {
            Set<Integer> used = loadUsedPorts();
            used.addAll(reserved);
            List<Integer> chosen = new ArrayList<>(count);
            for (int port = bounds[0]; port <= bounds[1] && chosen.size() < count; port++) {
                if (used.contains(port)) {
                    continue;
                }
                if (!portProbe.canBind(port)) {
                    continue;
                }
                chosen.add(port);
                used.add(port);
            }
            if (chosen.size() < count) {
                throw new BusinessException(ErrorCode.ENVIRONMENT_CREATE_FAILED, "端口范围内没有可用端口");
            }
            reserved.addAll(chosen);
            return List.copyOf(chosen);
        }
    }

    /**
     * 归还本进程保留的端口。数据库里的 port_mappings 仍然生效。
     */
    public void release(Collection<Integer> ports) {
        if (ports == null || ports.isEmpty()) {
            return;
        }
        synchronized (lock) {
            reserved.removeAll(ports);
        }
    }

    private Set<Integer> loadUsedPorts() {
        Set<Integer> used = new HashSet<>();
        List<String> rows = environmentMapper.selectActivePortMappings();
        if (rows == null) {
            return used;
        }
        for (String json : rows) {
            collectPorts(json, used);
        }
        return used;
    }

    private void collectPorts(String json, Set<Integer> used) {
        if (json == null || json.isBlank()) {
            return;
        }
        final JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            log.warn("忽略无法解析的端口映射");
            return;
        }
        if (node == null || !node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Integer port = hostPort(fields.next().getValue());
            if (port != null && port >= 1 && port <= 65535) {
                used.add(port);
            }
        }
    }

    private Integer hostPort(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            return value.intValue();
        }
        if (!value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        int colon = text.lastIndexOf(':');
        if (colon >= 0 && colon < text.length() - 1) {
            text = text.substring(colon + 1).trim();
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int[] portBounds() {
        String raw = properties.getPortRange() == null ? "" : properties.getPortRange().trim();
        int dash = raw.indexOf('-');
        if (dash <= 0 || dash != raw.lastIndexOf('-')) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "端口范围配置无效");
        }
        int start;
        int end;
        try {
            start = Integer.parseInt(raw.substring(0, dash).trim());
            end = Integer.parseInt(raw.substring(dash + 1).trim());
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "端口范围配置无效");
        }
        if (start < 1 || end > 65535 || start > end) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "端口范围配置无效");
        }
        return new int[]{start, end};
    }
}
