package com.sentinel.service.provision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.enums.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 解析任务依赖并解析到镜像目录。
 * 任务字段可能是 JSON 数组（如 ["mysql:8.0","redis:7.0"]）或逗号分隔字符串。
 */
@Component
public class DependencyCatalog {

    /**
     * 临时容器的默认口令。只放进容器环境变量和 compose，不写日志。
     */
    static final String DEFAULT_PASSWORD = "sentinel";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, DependencySpec> specs;

    public DependencyCatalog(ProvisioningProperties properties) {
        this.specs = buildSpecs(properties);
    }

    /**
     * 按配置顺序返回依赖。空列表表示不启动容器。未知名称抛出 VALIDATION_ERROR。
     */
    public List<DependencySpec> resolve(String raw) {
        List<String> tokens = readTokens(raw);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String trimmed = token.trim();
            String canonical = canonicalize(trimmed);
            if (canonical == null) {
                unknown.add(trimmed);
                continue;
            }
            names.add(canonical);
        }
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "未知依赖: " + String.join(", ", unknown));
        }
        List<DependencySpec> resolved = new ArrayList<>(names.size());
        for (String name : names) {
            resolved.add(specs.get(name));
        }
        return List.copyOf(resolved);
    }

    /**
     * 按名称取目录项。名称必须已经是目录中的规范名。
     */
    public DependencySpec require(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "未知依赖: " + name);
        }
        DependencySpec spec = specs.get(name.trim().toLowerCase(Locale.ROOT));
        if (spec == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "未知依赖: " + name.trim());
        }
        return spec;
    }

    private String canonicalize(String token) {
        String direct = token.toLowerCase(Locale.ROOT);
        if (specs.containsKey(direct)) {
            return direct;
        }
        int colon = token.indexOf(':');
        if (colon > 0) {
            String prefix = token.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (specs.containsKey(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    private List<String> readTokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{") || trimmed.startsWith("\"")) {
            return readJson(trimmed);
        }
        if ("null".equalsIgnoreCase(trimmed)) {
            return List.of();
        }
        return splitCsv(trimmed);
    }

    private List<String> readJson(String raw) {
        final JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "依赖列表必须是 JSON 数组或逗号分隔的名称");
        }
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isTextual()) {
            return splitCsv(node.asText());
        }
        if (!node.isArray()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "依赖列表必须是 JSON 数组或逗号分隔的名称");
        }
        List<String> tokens = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            if (!item.isTextual()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "依赖列表必须是字符串数组");
            }
            tokens.add(item.asText());
        }
        return tokens;
    }

    private List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split(",");
        List<String> tokens = new ArrayList<>(parts.length);
        for (String part : parts) {
            tokens.add(part);
        }
        return tokens;
    }

    private Map<String, DependencySpec> buildSpecs(ProvisioningProperties properties) {
        Map<String, DependencySpec> merged = new LinkedHashMap<>(builtinSpecs());
        if (properties == null || properties.getDependencies() == null) {
            return Map.copyOf(merged);
        }
        for (Map.Entry<String, ProvisioningProperties.Dependency> entry : properties.getDependencies().entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            String name = entry.getKey().trim().toLowerCase(Locale.ROOT);
            merged.put(name, merge(name, merged.get(name), entry.getValue()));
        }
        return Map.copyOf(merged);
    }

    private DependencySpec merge(String name, DependencySpec base, ProvisioningProperties.Dependency override) {
        String image = text(override.getImage());
        if (image == null && base != null) {
            image = base.getImage();
        }
        Integer port = override.getPort();
        if (port == null && base != null) {
            port = base.getContainerPort();
        }
        Map<String, String> env = copyEnv(override.getEnv());
        if (env.isEmpty() && base != null) {
            env = base.getEnvironment();
        }
        if (image == null || port == null || port < 1 || port > 65535) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "依赖 " + name + " 配置无效");
        }
        return new DependencySpec(name, image, port, env);
    }

    private Map<String, DependencySpec> builtinSpecs() {
        Map<String, DependencySpec> builtins = new LinkedHashMap<>();
        builtins.put("mysql", spec("mysql", "mysql:8.0", 3306, env(
                "MYSQL_ROOT_PASSWORD", DEFAULT_PASSWORD,
                "MYSQL_DATABASE", "test")));
        builtins.put("redis", spec("redis", "redis:7-alpine", 6379, Map.of()));
        builtins.put("postgres", spec("postgres", "postgres:16-alpine", 5432, env(
                "POSTGRES_PASSWORD", DEFAULT_PASSWORD)));
        builtins.put("mongodb", spec("mongodb", "mongo:7", 27017, Map.of()));
        builtins.put("rabbitmq", spec("rabbitmq", "rabbitmq:3-management-alpine", 5672, Map.of()));
        builtins.put("nginx", spec("nginx", "nginx:alpine", 80, Map.of()));
        return builtins;
    }

    private Map<String, String> env(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return values;
    }

    private DependencySpec spec(String name, String image, int port, Map<String, String> env) {
        return new DependencySpec(name, image, port, env);
    }

    private Map<String, String> copyEnv(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }

    private String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
