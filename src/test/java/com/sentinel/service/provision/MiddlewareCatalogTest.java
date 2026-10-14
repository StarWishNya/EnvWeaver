package com.sentinel.service.provision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 中间件目录加载与校验。
 */
@DisplayName("中间件目录加载")
class MiddlewareCatalogTest {

    @Test
    @DisplayName("classpath 目录包含约定的种类、默认版本和别名")
    void loadsShippedCatalog() {
        MiddlewareCatalog catalog = MiddlewareCatalog.load();

        assertThat(catalog.getRevision()).isEqualTo("2026-10-10.2");
        assertThat(catalog.kindNames()).containsExactly(
                "mysql", "redis", "postgres", "mongodb", "rabbitmq", "nginx", "kafka");

        MiddlewareKind mysql = catalog.kind("mysql");
        assertThat(mysql.getDefaultVersion()).isEqualTo("8.0");
        assertThat(mysql.getVersions()).extracting(CatalogVersion::getVersion).containsExactly("5.7", "8.0");
        assertThat(mysql.getVersions().get(1).getAliases()).containsExactly("8");
        assertThat(mysql.getVersions().get(1).getImage()).isEqualTo("mysql:8.0");
        assertThat(mysql.getVersions().get(1).getContainerPort()).isEqualTo(3306);
        assertThat(mysql.getVersions().get(1).getEnvironment())
                .containsEntry("MYSQL_ROOT_PASSWORD", "sentinel")
                .containsEntry("MYSQL_DATABASE", "test");
        assertThat(mysql.getVersions().get(1).getReadiness().getType()).isEqualTo("tcp");
        assertThat(mysql.getVersions().get(1).getReadiness().getPort()).isEqualTo(3306);
        assertThat(mysql.getVersions().get(1).getResources().getMemoryMb()).isEqualTo(512);

        CatalogVersion redis = catalog.kind("redis").getVersions().get(1);
        assertThat(catalog.kind("redis").getDefaultVersion()).isEqualTo("7");
        assertThat(redis.getAliases()).containsExactly("7.0", "7.2");
        assertThat(redis.getImage()).isEqualTo("redis:7-alpine");
        assertThat(catalog.kind("postgres").getDefaultVersion()).isEqualTo("16");
        assertThat(catalog.kind("nginx").getVersions()).extracting(CatalogVersion::getImage)
                .containsExactly("nginx:1.25-alpine", "nginx:1.27-alpine");
        CatalogVersion rabbitmq = catalog.kind("rabbitmq").getVersions().get(0);
        assertThat(catalog.kind("rabbitmq").getDefaultVersion()).isEqualTo("3.13");
        assertThat(rabbitmq.getImage()).isEqualTo("rabbitmq:3.13-management-alpine");
        assertThat(rabbitmq.getContainerPort()).isEqualTo(5672);
        assertThat(rabbitmq.getEnvironment())
                .containsEntry("RABBITMQ_DEFAULT_USER", "sentinel")
                .containsEntry("RABBITMQ_DEFAULT_PASS", "sentinel");
        assertThat(rabbitmq.getReadiness().getType()).isEqualTo("tcp");
        assertThat(rabbitmq.getReadiness().getPort()).isEqualTo(5672);

        CatalogVersion kafka = catalog.kind("kafka").getVersions().get(0);
        assertThat(kafka.getImage()).isEqualTo("apache/kafka:3.7.0");
        assertThat(kafka.getContainerPort()).isEqualTo(9092);
        assertThat(kafka.getEnvironment())
                .containsEntry("KAFKA_PROCESS_ROLES", "broker,controller")
                .containsEntry("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093")
                .containsEntry("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://${HOST}:${HOST_PORT}")
                .containsEntry("CLUSTER_ID", "MkU3OEVBNTcwNTJENDM2Qk");
    }

    @Test
    @DisplayName("环境变量只允许已知占位符")
    void rejectsUnknownAndUnclosedPlaceholders() {
        Map<String, Object> unknown = version("8.0", null, "mysql:8.0", 3306);
        unknown.put("env", Map.of("MYSQL_ROOT_PASSWORD", "PLAINTEXT://${BROKER}:${HOST_PORT}"));
        assertThatThrownBy(() -> MiddlewareCatalog.fromDocument(root(kind("8.0", List.of(unknown))), "test"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("未知占位符")
                .hasMessageContaining("${BROKER}");

        Map<String, Object> unclosed = version("8.0", null, "mysql:8.0", 3306);
        unclosed.put("env", Map.of("MYSQL_ROOT_PASSWORD", "PLAINTEXT://${HOST"));
        assertThatThrownBy(() -> MiddlewareCatalog.fromDocument(root(kind("8.0", List.of(unclosed))), "test"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("未闭合");

        Map<String, Object> accepted = version("8.0", null, "mysql:8.0", 3306);
        accepted.put("env", Map.of("MYSQL_ROOT_PASSWORD", "PLAINTEXT://${HOST}:${HOST_PORT}"));
        MiddlewareCatalog catalog = MiddlewareCatalog.fromDocument(root(kind("8.0", List.of(accepted))), "test");
        assertThat(catalog.kind("mysql").getVersions().get(0).getEnvironment())
                .containsEntry("MYSQL_ROOT_PASSWORD", "PLAINTEXT://${HOST}:${HOST_PORT}");
    }

    @Test
    @DisplayName("缺少镜像、端口或默认版本不在列表中时拒绝加载")
    void rejectsMissingImagePortAndUnknownDefault() {
        assertThatThrownBy(() -> MiddlewareCatalog.fromDocument(root(kind("8.0", List.of(
                version("8.0", null, null, 3306)))), "test"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("缺少 image");

        Map<String, Object> noPort = version("8.0", null, "mysql:8.0", 3306);
        noPort.remove("containerPort");
        assertThatThrownBy(() -> MiddlewareCatalog.fromDocument(root(kind("8.0", List.of(noPort))), "test"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("containerPort");

        assertThatThrownBy(() -> MiddlewareCatalog.fromDocument(root(kind("9.9", List.of(
                version("8.0", null, "mysql:8.0", 3306)))), "test"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("默认版本不在 versions 中");
    }

    @Test
    @DisplayName("重复的版本或别名被拒绝")
    void rejectsDuplicateVersionAndAlias() {
        assertThatThrownBy(() -> MiddlewareCatalog.fromDocument(root(kind("8.0", List.of(
                version("8.0", List.of("8"), "mysql:8.0", 3306),
                version("8.0", null, "mysql:8.0", 3306)))), "test"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("重复的版本");

        assertThatThrownBy(() -> MiddlewareCatalog.fromDocument(root(kind("8.0", List.of(
                version("5.7", List.of("old"), "mysql:5.7", 3306),
                version("8.0", List.of("old"), "mysql:8.0", 3306)))), "test"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("重复的别名");

        assertThatThrownBy(() -> MiddlewareCatalog.fromDocument(root(kind("8.0", List.of(
                version("8.0", List.of("8.0"), "mysql:8.0", 3306)))), "test"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("重复的别名");
    }

    @Test
    @DisplayName("schema、空文档、重复键和非法端口都拒绝")
    void rejectsMalformedDocuments() {
        Map<String, Object> wrongSchema = root(kind("8.0", List.of(version("8.0", null, "mysql:8.0", 3306))));
        wrongSchema.put("schemaVersion", 2);
        assertThatThrownBy(() -> MiddlewareCatalog.fromDocument(wrongSchema, "test"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("schemaVersion");

        assertThatThrownBy(() -> MiddlewareCatalog.fromDocument(null, "test"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("必须是映射");

        assertThatThrownBy(() -> MiddlewareCatalog.fromDocument(root(kind("8.0", List.of(
                version("8.0", null, "mysql:8.0", 0)))), "test"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("端口");

        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("type", "http");
        Map<String, Object> withReadiness = version("8.0", null, "mysql:8.0", 3306);
        withReadiness.put("readiness", readiness);
        assertThatThrownBy(() -> MiddlewareCatalog.fromDocument(root(kind("8.0", List.of(withReadiness))), "test"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("tcp");

        String duplicateKeys = """
                schemaVersion: 1
                revision: test
                kinds:
                  mysql:
                    defaultVersion: "8.0"
                    versions:
                      - version: "8.0"
                        image: mysql:8.0
                        containerPort: 3306
                  mysql:
                    defaultVersion: "8.0"
                    versions:
                      - version: "8.0"
                        image: mysql:8.0
                        containerPort: 3306
                """;
        assertThatThrownBy(() -> MiddlewareCatalog.load(
                new ByteArrayInputStream(duplicateKeys.getBytes(StandardCharsets.UTF_8)), "dup.yaml"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("重复的键: mysql");
    }

    private static Map<String, Object> root(Map<String, Object> kind) {
        Map<String, Object> kinds = new LinkedHashMap<>();
        kinds.put("mysql", kind);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("revision", "test");
        root.put("kinds", kinds);
        return root;
    }

    private static Map<String, Object> kind(String defaultVersion, List<Map<String, Object>> versions) {
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("defaultVersion", defaultVersion);
        kind.put("versions", versions);
        return kind;
    }

    private static Map<String, Object> version(String version, List<String> aliases, String image, int port) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("version", version);
        if (aliases != null) {
            row.put("aliases", aliases);
        }
        if (image != null) {
            row.put("image", image);
        }
        row.put("containerPort", port);
        return row;
    }
}
