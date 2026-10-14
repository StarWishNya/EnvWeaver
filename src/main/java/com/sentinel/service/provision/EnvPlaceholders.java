package com.sentinel.service.provision;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 目录环境变量里的启动期占位符。只允许 ${HOST} 和 ${HOST_PORT}。
 * 目录加载时校验；供给器在分配主机端口之后、启动容器之前替换。
 */
public final class EnvPlaceholders {

    public static final String HOST = "HOST";
    public static final String HOST_PORT = "HOST_PORT";

    private static final Set<String> NAMES = Set.of(HOST, HOST_PORT);

    private EnvPlaceholders() {
    }

    /**
     * 值里每个 ${...} 都必须是已知占位符，且花括号闭合。
     */
    public static void validate(String value, String path) {
        if (value == null || value.isEmpty()) {
            return;
        }
        int index = 0;
        while (index < value.length()) {
            int start = value.indexOf("${", index);
            if (start < 0) {
                return;
            }
            int end = value.indexOf('}', start + 2);
            if (end < 0) {
                throw new CatalogLoadException(path + " 的占位符未闭合");
            }
            String name = value.substring(start + 2, end);
            if (!NAMES.contains(name)) {
                throw new CatalogLoadException(path + " 含有未知占位符: ${" + name + "}");
            }
            index = end + 1;
        }
    }

    /**
     * 替换已知占位符。未知或未闭合的 ${...} 原样保留。不回头扫描替换进去的文本。
     */
    public static String substitute(String value, String host, int hostPort) {
        if (value == null || value.indexOf("${") < 0) {
            return value;
        }
        String hostText = host == null ? "" : host;
        String portText = Integer.toString(hostPort);
        StringBuilder out = new StringBuilder(value.length() + 16);
        int index = 0;
        while (index < value.length()) {
            int start = value.indexOf("${", index);
            if (start < 0) {
                out.append(value, index, value.length());
                break;
            }
            out.append(value, index, start);
            int end = value.indexOf('}', start + 2);
            if (end < 0) {
                out.append(value, start, value.length());
                break;
            }
            String name = value.substring(start + 2, end);
            if (HOST.equals(name)) {
                out.append(hostText);
            } else if (HOST_PORT.equals(name)) {
                out.append(portText);
            } else {
                out.append(value, start, end + 1);
            }
            index = end + 1;
        }
        return out.toString();
    }

    public static Map<String, String> substitute(Map<String, String> env, String host, int hostPort) {
        if (env == null || env.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            copy.put(entry.getKey(), substitute(entry.getValue(), host, hostPort));
        }
        return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }
}
