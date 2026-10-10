package com.sentinel.dto;

import com.sentinel.service.provision.EnvironmentTemplate;
import com.sentinel.service.provision.TemplateCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * 环境模板的公开视图。
 */
public record TemplateCatalogResponse(String revision, List<Template> templates) {

    public static TemplateCatalogResponse from(TemplateCatalog catalog) {
        List<Template> templates = new ArrayList<>();
        for (EnvironmentTemplate template : catalog.templates()) {
            templates.add(new Template(
                    template.getName(),
                    template.getDisplayName(),
                    template.getDescription(),
                    template.getDependencies()));
        }
        return new TemplateCatalogResponse(catalog.getRevision(), List.copyOf(templates));
    }

    public record Template(String name, String displayName, String description, List<String> dependencies) {
    }
}
