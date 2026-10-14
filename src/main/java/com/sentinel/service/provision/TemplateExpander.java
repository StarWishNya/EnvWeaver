package com.sentinel.service.provision;

import com.sentinel.config.BusinessException;
import com.sentinel.enums.ErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模板依赖与请求依赖合并。同一种类以显式依赖为准。
 */
public final class TemplateExpander {

    private TemplateExpander() {
    }

    public static List<String> expand(TemplateCatalog templates, String templateName, List<String> dependencies) {
        boolean named = templateName != null && !templateName.isBlank();
        boolean explicit = dependencies != null && !dependencies.isEmpty();
        if (!named) {
            return explicit ? List.copyOf(dependencies) : List.of();
        }
        EnvironmentTemplate template = templates.find(templateName);
        if (template == null) {
            throw unknownTemplate(templates, templateName.trim());
        }
        if (!explicit) {
            return template.getDependencies();
        }
        Map<String, String> merged = new LinkedHashMap<>();
        for (String token : template.getDependencies()) {
            merged.put(kindOf(token), token);
        }
        List<String> extras = new ArrayList<>();
        for (String raw : dependencies) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String token = raw.trim();
            String kind = kindOf(token);
            if (kind == null) {
                extras.add(token);
                continue;
            }
            if (merged.containsKey(kind)) {
                merged.put(kind, token);
            } else {
                extras.add(token);
            }
        }
        List<String> result = new ArrayList<>(merged.values());
        result.addAll(extras);
        return List.copyOf(result);
    }

    private static String kindOf(String token) {
        int colon = token.indexOf(':');
        String kind = (colon < 0 ? token : token.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
        if (kind.isEmpty() || (colon >= 0 && token.substring(colon + 1).isBlank())) {
            return null;
        }
        return kind;
    }

    private static BusinessException unknownTemplate(TemplateCatalog templates, String name) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("candidates", templates.names());
        return new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                "未知模板: " + name + "，可选模板: " + String.join(", ", templates.names()),
                data);
    }
}
