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
 * 命名环境模板。每条依赖必须能在中间件目录里解析。
 */
public final class TemplateCatalog {

    public static final int SCHEMA_VERSION = 1;
    public static final String CLASSPATH = "catalog/templates.yaml";

    private final String revision;
    private final Map<String, EnvironmentTemplate> templates;

    private TemplateCatalog(String revision, Map<String, EnvironmentTemplate> templates) {
        this.revision = revision;
        this.templates = Collections.unmodifiableMap(new LinkedHashMap<>(templates));
    }

    public static TemplateCatalog load(MiddlewareCatalog middleware) {
        try (InputStream input = TemplateCatalog.class.getClassLoader().getResourceAsStream(CLASSPATH)) {
            if (input == null) {
                throw new CatalogLoadException("找不到环境模板: " + CLASSPATH);
            }
            return load(input, CLASSPATH, middleware);
        } catch (IOException ex) {
            throw new CatalogLoadException("读取环境模板失败: " + CLASSPATH, ex);
        }
    }

    static TemplateCatalog load(InputStream input, String source, MiddlewareCatalog middleware) {
        return fromDocument(CatalogYaml.load(input, source), source, middleware);
    }

    static TemplateCatalog fromDocument(Object document, String source, MiddlewareCatalog middleware) {
        if (middleware == null) {
            throw new CatalogLoadException(source + " 缺少中间件目录");
        }
        Map<String, Object> root = mapping(document, source);
        int schemaVersion = integer(required(root, "schemaVersion", source), source + ".schemaVersion");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new CatalogLoadException(source + " schemaVersion 必须是 " + SCHEMA_VERSION);
        }
        String revision = text(required(root, "revision", source), source + ".revision");
        if (revision == null) {
            throw new CatalogLoadException(source + ".revision 不能为空");
        }
        Object rawTemplates = required(root, "templates", source);
        if (!(rawTemplates instanceof List<?> rows) || rows.isEmpty()) {
            throw new CatalogLoadException(source + ".templates 必须是非空列表");
        }
        Map<String, EnvironmentTemplate> templates = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            EnvironmentTemplate template = readTemplate(rows.get(i), source + ".templates[" + i + "]", middleware);
            String key = template.getName().toLowerCase(Locale.ROOT);
            if (templates.containsKey(key)) {
                throw new CatalogLoadException(source + " 重复的模板: " + template.getName());
            }
            templates.put(key, template);
        }
        return new TemplateCatalog(revision, templates);
    }

    public String getRevision() {
        return revision;
    }

    public List<EnvironmentTemplate> templates() {
        return List.copyOf(templates.values());
    }

    public List<String> names() {
        List<String> names = new ArrayList<>(templates.size());
        for (EnvironmentTemplate template : templates.values()) {
            names.add(template.getName());
        }
        return List.copyOf(names);
    }

    public EnvironmentTemplate find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return templates.get(name.trim().toLowerCase(Locale.ROOT));
    }

    private static EnvironmentTemplate readTemplate(Object raw, String path, MiddlewareCatalog middleware) {
        Map<String, Object> node = mapping(raw, path);
        String name = text(required(node, "name", path), path + ".name");
        if (name == null || name.indexOf(' ') >= 0 || name.indexOf(':') >= 0) {
            throw new CatalogLoadException(path + ".name 不能为空，且不能含空格或冒号");
        }
        String displayName = text(required(node, "displayName", path), path + ".displayName");
        if (displayName == null) {
            throw new CatalogLoadException(path + ".displayName 不能为空");
        }
        String description = text(node.get("description"), path + ".description");
        Object dependenciesRaw = required(node, "dependencies", path);
        if (!(dependenciesRaw instanceof List<?> rows) || rows.isEmpty()) {
            throw new CatalogLoadException(path + ".dependencies 必须是非空列表");
        }
        List<String> dependencies = new ArrayList<>();
        Set<String> kinds = new LinkedHashSet<>();
        for (Object row : rows) {
            String token = text(row, path + ".dependencies");
            if (token == null) {
                throw new CatalogLoadException(path + ".dependencies 含有空项");
            }
            ResolvedDependency resolved = MiddlewareResolver.resolveOne(middleware, token);
            if (resolved == null) {
                throw new CatalogLoadException(path + " 的依赖无法解析: " + token);
            }
            if (!kinds.add(resolved.getKind())) {
                throw new CatalogLoadException(path + " 重复的依赖种类: " + resolved.getKind());
            }
            dependencies.add(token);
        }
        return new EnvironmentTemplate(name, displayName, description == null ? "" : description, dependencies);
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
        if (!(raw instanceof String) && !(raw instanceof Number) && !(raw instanceof Boolean)) {
            throw new CatalogLoadException(path + " 必须是标量");
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? null : text;
    }

    private static int integer(Object raw, String path) {
        if (raw instanceof Integer value) {
            return value;
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
}
