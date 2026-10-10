package com.sentinel.service.provision;

import lombok.Getter;

import java.util.List;

/**
 * 一种中间件及其全部版本。
 */
@Getter
public class MiddlewareKind {

    private final String kind;
    private final String defaultVersion;
    private final List<CatalogVersion> versions;

    public MiddlewareKind(String kind, String defaultVersion, List<CatalogVersion> versions) {
        this.kind = kind;
        this.defaultVersion = defaultVersion;
        this.versions = versions == null ? List.of() : List.copyOf(versions);
    }
}
