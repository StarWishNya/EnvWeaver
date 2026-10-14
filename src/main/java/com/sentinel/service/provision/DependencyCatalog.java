package com.sentinel.service.provision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.enums.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final MiddlewareCatalog middlewareCatalog;
    private final Map<String, DependencySpec> specs;

    public DependencyCatalog(ProvisioningProperties properties) {
        this(MiddlewareCatalog.load(), properties);
    }

    @Autowired
    public DependencyCatalog(MiddlewareCatalog middlewareCatalog, ProvisioningProperties properties) {
        this.middlewareCatalog = middlewareCatalog;
        this.specs = buildSpecs(properties);
    }

    public MiddlewareCatalog middlewareCatalog() {
        return middlewareCatalog;
    }

    /**
     * 按配置顺序返回依赖。空列表表示不启动容器。未知名称抛出 VALIDATION_ERROR。
     */
    public List<DependencySpec> resolve(String raw) {
        return resolveTokens(readTokens(raw));
    }

    /**
     * 解析已经拆开的依赖记号。
     * 未写版本，或写的是该种类的默认版本时，{@code sentinel.provisioning.dependencies} 覆盖目录。
     * 其它版本用目录条目。目录里没有、但配置里写全了镜像和端口的种类也可以用。
     */
    public List<DependencySpec> resolveTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        List<DependencySpec> slots = new ArrayList<>();
        Map<String, Integer> index = new LinkedHashMap<>();
        List<String> catalogTokens = new ArrayList<>();
        for (String raw : tokens) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String token = raw.trim();
            TokenParts parts = tokenParts(token);
            if (parts == null) {
                catalogTokens.add(token);
                continue;
            }
            if (middlewareCatalog.kind(parts.kind) == null
                    && parts.version == null
                    && specs.containsKey(parts.kind)) {
                putSlot(slots, index, parts.kind, specs.get(parts.kind));
                continue;
            }
            catalogTokens.add(token);
            if (middlewareCatalog.kind(parts.kind) != null) {
                putSlot(slots, index, parts.kind, null);
            }
        }
        for (ResolvedDependency dependency : MiddlewareResolver.resolve(middlewareCatalog, catalogTokens)) {
            putSlot(slots, index, dependency.getKind(), specFor(dependency));
        }
        for (DependencySpec spec : slots) {
            if (spec == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "依赖解析失败");
            }
        }
        return List.copyOf(slots);
    }

    /**
     * 按种类名取当前生效的目录项。配置覆盖优先于文件目录的默认版本。
     */
    public DependencySpec require(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "未知依赖: " + name);
        }
        String kind = name.trim().toLowerCase(Locale.ROOT);
        DependencySpec spec = specs.get(kind);
        if (spec != null) {
            return spec;
        }
        return resolve(kind).get(0);
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
        Map<String, DependencySpec> merged = new LinkedHashMap<>();
        if (properties == null || properties.getDependencies() == null) {
            return Map.of();
        }
        for (Map.Entry<String, ProvisioningProperties.Dependency> entry : properties.getDependencies().entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            String name = entry.getKey().trim().toLowerCase(Locale.ROOT);
            merged.put(name, merge(name, fileDefault(name), entry.getValue()));
        }
        return Map.copyOf(merged);
    }

    private DependencySpec fileDefault(String kind) {
        MiddlewareKind defined = middlewareCatalog.kind(kind);
        if (defined == null) {
            return null;
        }
        for (CatalogVersion version : defined.getVersions()) {
            if (version.getVersion().equals(defined.getDefaultVersion())) {
                return new DependencySpec(kind, version.getImage(), version.getContainerPort(), version.getEnvironment());
            }
        }
        return null;
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

    private DependencySpec specFor(ResolvedDependency dependency) {
        CatalogVersion version = dependency.getCatalogVersion();
        MiddlewareKind kind = middlewareCatalog.kind(dependency.getKind());
        DependencySpec override = specs.get(dependency.getKind());
        if (override != null && kind != null && version.getVersion().equals(kind.getDefaultVersion())) {
            return override;
        }
        return new DependencySpec(
                dependency.getKind(), version.getImage(), version.getContainerPort(), version.getEnvironment());
    }

    private static TokenParts tokenParts(String token) {
        int colon = token.indexOf(':');
        String kind = (colon < 0 ? token : token.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
        String version = colon < 0 ? null : token.substring(colon + 1).trim();
        if (kind.isEmpty() || (version != null && (version.isEmpty() || version.indexOf(':') >= 0))) {
            return null;
        }
        return new TokenParts(kind, version);
    }

    private static void putSlot(List<DependencySpec> slots,
                                Map<String, Integer> index,
                                String kind,
                                DependencySpec spec) {
        Integer at = index.get(kind);
        if (at == null) {
            index.put(kind, slots.size());
            slots.add(spec);
            return;
        }
        if (spec != null) {
            slots.set(at, spec);
        }
    }

    private record TokenParts(String kind, String version) {
    }
}
