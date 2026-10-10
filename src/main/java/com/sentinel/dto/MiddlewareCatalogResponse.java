package com.sentinel.dto;

import com.sentinel.service.provision.CatalogVersion;
import com.sentinel.service.provision.MiddlewareCatalog;
import com.sentinel.service.provision.MiddlewareKind;

import java.util.ArrayList;
import java.util.List;

/**
 * 中间件目录的公开视图。不含环境变量，避免把默认口令返回给浏览器。
 */
public record MiddlewareCatalogResponse(String revision, List<Kind> kinds) {

    public static MiddlewareCatalogResponse from(MiddlewareCatalog catalog) {
        List<Kind> kinds = new ArrayList<>();
        for (MiddlewareKind kind : catalog.kinds()) {
            List<Version> versions = new ArrayList<>();
            for (CatalogVersion version : kind.getVersions()) {
                versions.add(new Version(
                        version.getVersion(),
                        version.getAliases(),
                        version.getImage(),
                        version.getContainerPort()));
            }
            kinds.add(new Kind(kind.getKind(), kind.getDefaultVersion(), List.copyOf(versions)));
        }
        return new MiddlewareCatalogResponse(catalog.getRevision(), List.copyOf(kinds));
    }

    public record Kind(String kind, String defaultVersion, List<Version> versions) {
    }

    public record Version(String version, List<String> aliases, String image, int containerPort) {
    }
}
