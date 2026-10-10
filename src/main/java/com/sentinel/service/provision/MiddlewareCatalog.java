package com.sentinel.service.provision;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 运维维护的中间件目录。schemaVersion 必须是 1，加载时校验镜像、端口、默认版本和别名。
 */
public final class MiddlewareCatalog {

    public static final int SCHEMA_VERSION = 1;
    public static final String CLASSPATH = "catalog/middleware.yaml";

    private final String revision;
    private final Map<String, MiddlewareKind> kinds;

    private MiddlewareCatalog(String revision, Map<String, MiddlewareKind> kinds) {
        this.revision = revision;
        this.kinds = Collections.unmodifiableMap(new LinkedHashMap<>(kinds));
    }

    public static MiddlewareCatalog load() {
        try (InputStream input = MiddlewareCatalog.class.getClassLoader().getResourceAsStream(CLASSPATH)) {
            if (input == null) {
                throw new CatalogLoadException("找不到中间件目录: " + CLASSPATH);
            }
            return load(input, CLASSPATH);
        } catch (IOException ex) {
            throw new CatalogLoadException("读取中间件目录失败: " + CLASSPATH, ex);
        }
    }

    static MiddlewareCatalog load(InputStream input, String source) {
        return fromDocument(CatalogYaml.load(input, source), source);
    }

    static MiddlewareCatalog fromDocument(Object document, String source) {
        Map<String, Object> root = mapping(document, source);
        int schemaVersion = integer(required(root, "schemaVersion", source), source + ".schemaVersion");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new CatalogLoadException(source + " schemaVersion 必须是 " + SCHEMA_VERSION);
        }
        String revision = text(required(root, "revision", source), source + ".revision");
        if (revision == null) {
            throw new CatalogLoadException(source + ".revision 不能为空");
        }
        Map<String, Object> kindsNode = mapping(required(root, "kinds", source), source + ".kinds");
        if (kindsNode.isEmpty()) {
            throw new CatalogLoadException(source + ".kinds 不能为空");
        }
        Map<String, MiddlewareKind> kinds = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : kindsNode.entrySet()) {
            String kindName = normalizeKind(entry.getKey(), source + ".kinds");
            if (kinds.containsKey(kindName)) {
                throw new CatalogLoadException(source + " 重复的中间件: " + kindName);
            }
            kinds.put(kindName, readKind(kindName, entry.getValue(), source + ".kinds." + kindName));
        }
        return new MiddlewareCatalog(revision, kinds);
    }

    public String getRevision() {
        return revision;
    }

    public List<String> kindNames() {
        return List.copyOf(kinds.keySet());
    }

    public MiddlewareKind kind(String name) {
        if (name == null) {
            return null;
        }
        return kinds.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public List<MiddlewareKind> kinds() {
        return List.copyOf(kinds.values());
    }

    private static MiddlewareKind readKind(String kindName, Object raw, String path) {
        Map<String, Object> node = mapping(raw, path);
        String defaultVersion = text(required(node, "defaultVersion", path), path + ".defaultVersion");
        if (defaultVersion == null) {
            throw new CatalogLoadException(path + ".defaultVersion 不能为空");
        }
        Object versionsRaw = required(node, "versions", path);
        if (!(versionsRaw instanceof List<?> versions) || versions.isEmpty()) {
            throw new CatalogLoadException(path + ".versions 必须是非空列表");
        }
        List<CatalogVersion> parsed = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();
        boolean defaultPresent = false;
        for (int i = 0; i < versions.size(); i++) {
            CatalogVersion version = readVersion(versions.get(i), path + ".versions[" + i + "]");
            claim(taken, version.getVersion(), path, "版本");
            for (String alias : version.getAliases()) {
                claim(taken, alias, path, "别名");
            }
            if (version.getVersion().equals(defaultVersion)) {
                defaultPresent = true;
            }
            parsed.add(version);
        }
        if (!defaultPresent) {
            throw new CatalogLoadException(path + " 的默认版本不在 versions 中: " + defaultVersion);
        }
        return new MiddlewareKind(kindName, defaultVersion, parsed);
    }

    private static CatalogVersion readVersion(Object raw, String path) {
        Map<String, Object> node = mapping(raw, path);
        String version = text(required(node, "version", path), path + ".version");
        if (version == null) {
            throw new CatalogLoadException(path + ".version 不能为空");
        }
        String image = text(required(node, "image", path), path + ".image");
        if (image == null) {
            throw new CatalogLoadException(path + " 缺少 image");
        }
        int port = port(required(node, "containerPort", path), path + ".containerPort");
        List<String> aliases = readAliases(node.get("aliases"), path + ".aliases");
        Map<String, String> env = readEnv(node.get("env"), path + ".env");
        ReadinessSpec readiness = readReadiness(node.get("readiness"), port, path + ".readiness");
        ResourceHints resources = readResources(node.get("resources"), path + ".resources");
        return new CatalogVersion(version, aliases, image, port, env, readiness, resources);
    }

    private static List<String> readAliases(Object raw, String path) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> values)) {
            throw new CatalogLoadException(path + " 必须是列表");
        }
        List<String> aliases = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object value : values) {
            String alias = text(value, path);
            if (alias == null) {
                throw new CatalogLoadException(path + " 含有空别名");
            }
            if (!seen.add(alias)) {
                throw new CatalogLoadException(path + " 重复的别名: " + alias);
            }
            aliases.add(alias);
        }
        return aliases;
    }

    private static Map<String, String> readEnv(Object raw, String path) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, Object> node = mapping(raw, path);
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = text(entry.getKey(), path);
            if (key == null) {
                throw new CatalogLoadException(path + " 含有空的环境变量名");
            }
            if (entry.getValue() == null) {
                throw new CatalogLoadException(path + "." + key + " 不能为空");
            }
            String rendered = String.valueOf(entry.getValue());
            EnvPlaceholders.validate(rendered, path + "." + key);
            env.put(key, rendered);
        }
        return env;
    }

    private static ReadinessSpec readReadiness(Object raw, int containerPort, String path) {
        if (raw == null) {
            return new ReadinessSpec("tcp", containerPort, 0);
        }
        Map<String, Object> node = mapping(raw, path);
        String type = text(node.get("type"), path + ".type");
        if (type == null) {
            type = "tcp";
        }
        if (!"tcp".equals(type)) {
            throw new CatalogLoadException(path + ".type 只支持 tcp");
        }
        int port = node.get("port") == null ? containerPort : port(node.get("port"), path + ".port");
        int grace = 0;
        if (node.get("startupGraceSeconds") != null) {
            grace = integer(node.get("startupGraceSeconds"), path + ".startupGraceSeconds");
            if (grace < 0) {
                throw new CatalogLoadException(path + ".startupGraceSeconds 不能为负");
            }
        }
        return new ReadinessSpec(type, port, grace);
    }

    private static ResourceHints readResources(Object raw, String path) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> node = mapping(raw, path);
        Integer memory = node.get("memoryMb") == null ? null : integer(node.get("memoryMb"), path + ".memoryMb");
        if (memory != null && memory < 1) {
            throw new CatalogLoadException(path + ".memoryMb 必须大于 0");
        }
        Double cpu = node.get("cpu") == null ? null : decimal(node.get("cpu"), path + ".cpu");
        if (cpu != null && cpu <= 0) {
            throw new CatalogLoadException(path + ".cpu 必须大于 0");
        }
        return new ResourceHints(memory, cpu);
    }

    private static void claim(Set<String> taken, String token, String path, String label) {
        if (!taken.add(token)) {
            throw new CatalogLoadException(path + " 重复的" + label + ": " + token);
        }
    }

    private static String normalizeKind(String name, String path) {
        String text = text(name, path);
        if (text == null || !text.equals(text.toLowerCase(Locale.ROOT)) || text.indexOf(':') >= 0 || text.indexOf(' ') >= 0) {
            throw new CatalogLoadException(path + " 的名称必须是小写且不含冒号或空格");
        }
        return text;
    }

    private static Object required(Map<String, Object> node, String key, String path) {
        if (!node.containsKey(key) || node.get(key) == null) {
            throw new CatalogLoadException(path + " 缺少 " + key);
        }
        return node.get(key);
    }

    private static Map<String, Object> mapping(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new CatalogLoadException(path + " 必须是映射");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new CatalogLoadException(path + " 的键必须是字符串");
            }
            copy.put(key, entry.getValue());
        }
        return copy;
    }

    private static String text(Object raw, String path) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value) && !(raw instanceof Number) && !(raw instanceof Boolean)) {
            throw new CatalogLoadException(path + " 必须是标量");
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? null : text;
    }

    private static int integer(Object raw, String path) {
        if (raw instanceof Integer value) {
            return value;
        }
        if (raw instanceof Long value) {
            if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
                throw new CatalogLoadException(path + " 超出整数范围");
            }
            return value.intValue();
        }
        if (raw instanceof String value) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ex) {
                throw new CatalogLoadException(path + " 必须是整数");
            }
        }
        throw new CatalogLoadException(path + " 必须是整数");
    }

    private static double decimal(Object raw, String path) {
        if (raw instanceof Number value) {
            return value.doubleValue();
        }
        if (raw instanceof String value) {
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException ex) {
                throw new CatalogLoadException(path + " 必须是数字");
            }
        }
        throw new CatalogLoadException(path + " 必须是数字");
    }

    private static int port(Object raw, String path) {
        int port = integer(raw, path);
        if (port < 1 || port > 65535) {
            throw new CatalogLoadException(path + " 必须是 1 到 65535 的端口");
        }
        return port;
    }
}
