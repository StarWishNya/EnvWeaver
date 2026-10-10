package com.sentinel.service.provision;

import lombok.Getter;

/**
 * 一次解析结果。requested 保留调用方写法，canonical 是 kind:version。
 */
@Getter
public class ResolvedDependency {

    private final String requested;
    private final String kind;
    private final String version;
    private final CatalogVersion catalogVersion;

    public ResolvedDependency(String requested, String kind, String version, CatalogVersion catalogVersion) {
        this.requested = requested;
        this.kind = kind;
        this.version = version;
        this.catalogVersion = catalogVersion;
    }

    public String canonical() {
        return kind + ":" + version;
    }
}
