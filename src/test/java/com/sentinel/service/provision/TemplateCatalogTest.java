package com.sentinel.service.provision;

import com.sentinel.config.BusinessException;
import com.sentinel.enums.ErrorCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 环境模板加载与合并。
 */
@DisplayName("环境模板")
class TemplateCatalogTest {

    private static MiddlewareCatalog middleware;
    private static TemplateCatalog templates;

    @BeforeAll
    static void load() {
        middleware = MiddlewareCatalog.load();
        templates = TemplateCatalog.load(middleware);
    }

    @Test
    @DisplayName("内置模板带中文名和可解析的依赖")
    void loadsShippedTemplates() {
        assertThat(templates.names()).containsExactly("web-stack", "data-stack", "mq-stack");
        EnvironmentTemplate data = templates.find("DATA-STACK");
        assertThat(data.getDisplayName()).isEqualTo("数据技术栈");
        assertThat(data.getDependencies()).containsExactly("postgres:16", "redis:7");
        assertThat(templates.find("web-stack").getDependencies()).containsExactly("mysql:8.0", "redis:7", "nginx");
        assertThat(templates.find("mq-stack").getDescription()).contains("Kafka");
    }

    @Test
    @DisplayName("模板依赖无法解析或种类重复时拒绝加载")
    void rejectsUnresolvedOrDuplicateDependencies() {
        assertThatThrownBy(() -> TemplateCatalog.fromDocument(document(List.of(
                template("bad", "坏模板", List.of("oracle")))), "templates.yaml", middleware))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("无法解析");

        assertThatThrownBy(() -> TemplateCatalog.fromDocument(document(List.of(
                template("dup", "重复", List.of("mysql:8.0", "mysql:5.7")))), "templates.yaml", middleware))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("重复的依赖种类");

        assertThatThrownBy(() -> TemplateCatalog.fromDocument(document(List.of(
                template("one", "一", List.of("mysql")),
                template("one", "二", List.of("redis")))), "templates.yaml", middleware))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("重复的模板");
    }

    @Test
    @DisplayName("只给模板时展开依赖；同种类以显式依赖为准")
    void expandsAndMerges() {
        assertThat(TemplateExpander.expand(templates, "data-stack", null))
                .containsExactly("postgres:16", "redis:7");
        assertThat(TemplateExpander.expand(templates, "data-stack", List.of()))
                .containsExactly("postgres:16", "redis:7");

        assertThat(TemplateExpander.expand(templates, "data-stack", List.of("redis:6.2", "nginx:1.25")))
                .containsExactly("postgres:16", "redis:6.2", "nginx:1.25");

        assertThat(TemplateExpander.expand(templates, "  ", List.of("mysql"))).containsExactly("mysql");
    }

    @Test
    @DisplayName("未知模板返回候选名称")
    void unknownTemplateListsCandidates() {
        BusinessException failure = catchThrowableOfType(
                () -> TemplateExpander.expand(templates, "nope", List.of()), BusinessException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.getCode());
        assertThat(failure.getMessage()).contains("未知模板: nope").contains("data-stack");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) failure.getData();
        assertThat(data.get("candidates")).isEqualTo(List.of("web-stack", "data-stack", "mq-stack"));
    }

    private static Map<String, Object> document(List<Map<String, Object>> rows) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("revision", "test");
        root.put("templates", rows);
        return root;
    }

    private static Map<String, Object> template(String name, String displayName, List<String> dependencies) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("displayName", displayName);
        row.put("description", "说明");
        row.put("dependencies", dependencies);
        return row;
    }
}
