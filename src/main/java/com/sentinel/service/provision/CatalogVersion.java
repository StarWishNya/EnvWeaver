package com.sentinel.service.provision;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 某个中间件的一个版本。environment 可能含密码，不生成 toString。
 */
@Getter
public class CatalogVersion {

    private final String version;
    private final List<String> aliases;
    private final String image;
    private final int containerPort;
    private final Map<String, String> environment;
    private final ReadinessSpec readiness;
    private final ResourceHints resources;

    public CatalogVersion(String version,
                          List<String> aliases,
                          String image,
                          int containerPort,
                          Map<String, String> environment,
                          ReadinessSpec readiness,
                          ResourceHints resources) {
        this.version = version;
        this.aliases = aliases == null || aliases.isEmpty() ? List.of() : List.copyOf(aliases);
        this.image = image;
        this.containerPort = containerPort;
        this.environment = copy(environment);
        this.readiness = readiness;
        this.resources = resources;
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
