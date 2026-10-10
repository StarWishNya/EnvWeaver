package com.sentinel.service.provision;

import lombok.Getter;

import java.util.List;

/**
 * 命名的依赖组合。dependencies 是目录里的 kind 或 kind:version 记号。
 */
@Getter
public class EnvironmentTemplate {

    private final String name;
    private final String displayName;
    private final String description;
    private final List<String> dependencies;

    public EnvironmentTemplate(String name, String displayName, String description, List<String> dependencies) {
        this.name = name;
        this.displayName = displayName;
        this.description = description;
        this.dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }
}
