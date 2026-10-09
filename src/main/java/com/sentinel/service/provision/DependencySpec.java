package com.sentinel.service.provision;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 目录中的一项依赖。environment 可能含密码，默认 toString 不输出字段值以外的内容；
 * 这里不生成包含环境变量的 toString。
 */
@Getter
public class DependencySpec {

    private final String name;
    private final String image;
    private final int containerPort;
    private final Map<String, String> environment;

    public DependencySpec(String name, String image, int containerPort, Map<String, String> environment) {
        this.name = name;
        this.image = image;
        this.containerPort = containerPort;
        this.environment = copy(environment);
    }

    private static Map<String, String> copy(Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
    }
}
