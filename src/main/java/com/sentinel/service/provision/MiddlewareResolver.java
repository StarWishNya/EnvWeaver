package com.sentinel.service.provision;

import com.sentinel.config.BusinessException;
import com.sentinel.enums.ErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 kind 或 kind:version 解析成目录版本。未知种类或版本抛出带候选列表的 VALIDATION_ERROR。
 */
public final class MiddlewareResolver {

    private MiddlewareResolver() {
    }

    public static List<ResolvedDependency> resolve(MiddlewareCatalog catalog, List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        List<ResolvedDependency> resolved = new ArrayList<>();
        Map<String, Integer> indexByKind = new LinkedHashMap<>();
        List<ResolveFailure> failures = new ArrayList<>();
        for (String raw : tokens) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String token = raw.trim();
            ResolvedDependency one = resolveToken(catalog, token, failures);
            if (one == null) {
                continue;
            }
            Integer existing = indexByKind.putIfAbsent(one.getKind(), resolved.size());
            if (existing == null) {
                resolved.add(one);
            } else {
                resolved.set(existing, one);
            }
        }
        if (!failures.isEmpty()) {
            throw validation(failures);
        }
        return List.copyOf(resolved);
    }

    /**
     * 供模板加载使用。无法解析时返回 null，不抛业务异常。
     */
    static ResolvedDependency resolveOne(MiddlewareCatalog catalog, String token) {
        List<ResolveFailure> failures = new ArrayList<>();
        ResolvedDependency resolved = resolveToken(catalog, token, failures);
        return failures.isEmpty() ? resolved : null;
    }

    private static ResolvedDependency resolveToken(MiddlewareCatalog catalog, String token, List<ResolveFailure> failures) {
        int colon = token.indexOf(':');
        String kindName = (colon < 0 ? token : token.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
        String requested = colon < 0 ? null : token.substring(colon + 1).trim();
        if (kindName.isEmpty() || (colon >= 0 && (requested == null || requested.isEmpty() || requested.indexOf(':') >= 0))) {
            failures.add(new ResolveFailure(
                    ResolveFailure.UNKNOWN_KIND,
                    token,
                    "未知依赖: " + token,
                    catalog.kindNames()));
            return null;
        }
        MiddlewareKind kind = catalog.kind(kindName);
        if (kind == null) {
            failures.add(new ResolveFailure(
                    ResolveFailure.UNKNOWN_KIND,
                    token,
                    "未知依赖: " + token + "，可选种类: " + String.join(", ", catalog.kindNames()),
                    catalog.kindNames()));
            return null;
        }
        if (requested == null) {
            CatalogVersion version = find(kind, kind.getDefaultVersion());
            return new ResolvedDependency(token, kind.getKind(), version.getVersion(), version);
        }
        CatalogVersion version = find(kind, requested);
        if (version == null) {
            List<String> versions = versionNames(kind);
            failures.add(new ResolveFailure(
                    ResolveFailure.UNKNOWN_VERSION,
                    token,
                    "未知依赖版本: " + token + "，可选版本: " + String.join(", ", versions),
                    versions));
            return null;
        }
        return new ResolvedDependency(token, kind.getKind(), version.getVersion(), version);
    }

    private static CatalogVersion find(MiddlewareKind kind, String requested) {
        for (CatalogVersion version : kind.getVersions()) {
            if (version.getVersion().equals(requested)) {
                return version;
            }
            if (version.getAliases().contains(requested)) {
                return version;
            }
        }
        return null;
    }

    public static List<String> versionNames(MiddlewareKind kind) {
        List<String> names = new ArrayList<>();
        for (CatalogVersion version : kind.getVersions()) {
            names.add(version.getVersion());
        }
        return List.copyOf(names);
    }

    private static BusinessException validation(List<ResolveFailure> failures) {
        List<String> messages = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ResolveFailure failure : failures) {
            messages.add(failure.getMessage());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", failure.getCode());
            row.put("token", failure.getToken());
            row.put("candidates", failure.getCandidates());
            rows.add(row);
        }
        data.put("errors", rows);
        if (failures.size() == 1) {
            data.put("code", failures.get(0).getCode());
            data.put("candidates", failures.get(0).getCandidates());
        }
        return new BusinessException(ErrorCode.VALIDATION_ERROR, String.join("; ", messages), data);
    }
}
