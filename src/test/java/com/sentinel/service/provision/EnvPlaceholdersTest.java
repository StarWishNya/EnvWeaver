package com.sentinel.service.provision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 目录环境变量占位符的校验与替换。
 */
@DisplayName("目录环境变量占位符")
class EnvPlaceholdersTest {

    @Test
    @DisplayName("启动时替换主机和主机端口，不改动其余文本")
    void substitutesHostAndHostPort() {
        assertThat(EnvPlaceholders.substitute("PLAINTEXT://${HOST}:${HOST_PORT}", "127.0.0.1", 36210))
                .isEqualTo("PLAINTEXT://127.0.0.1:36210");
        assertThat(EnvPlaceholders.substitute("a${HOST}b${HOST}c${HOST_PORT}", "localhost", 9))
                .isEqualTo("alocalhostblocalhostc9");
        assertThat(EnvPlaceholders.substitute("price is $5", "h", 1)).isEqualTo("price is $5");
        assertThat(EnvPlaceholders.substitute((String) null, "h", 1)).isNull();
    }

    @Test
    @DisplayName("替换进去的文本不再扫描，未知占位符原样保留")
    void doesNotRescanReplacementAndLeavesUnknown() {
        assertThat(EnvPlaceholders.substitute("x${HOST}y", "${HOST_PORT}", 36210))
                .isEqualTo("x${HOST_PORT}y");
        assertThat(EnvPlaceholders.substitute("${FOO}-${HOST}", "db", 7)).isEqualTo("${FOO}-db");
        assertThat(EnvPlaceholders.substitute("PLAINTEXT://${HOST", "db", 7)).isEqualTo("PLAINTEXT://${HOST");
    }

    @Test
    @DisplayName("映射替换返回新表，不改原来的目录环境变量")
    void substitutesMapWithoutMutatingSource() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://${HOST}:${HOST_PORT}");
        source.put("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093");

        Map<String, String> result = EnvPlaceholders.substitute(source, "localhost", 36221);

        assertThat(result)
                .containsEntry("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:36221")
                .containsEntry("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093");
        assertThat(source).containsEntry("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://${HOST}:${HOST_PORT}");
        assertThat(EnvPlaceholders.substitute(Map.of(), "h", 1)).isEmpty();
        assertThat(EnvPlaceholders.substitute((Map<String, String>) null, "h", 1)).isEmpty();
    }

    @Test
    @DisplayName("只接受 HOST 与 HOST_PORT")
    void validateAllowsOnlyKnownPlaceholders() {
        EnvPlaceholders.validate("PLAINTEXT://${HOST}:${HOST_PORT}", "env.KAFKA");
        EnvPlaceholders.validate("sentinel", "env.PASSWORD");
        EnvPlaceholders.validate(null, "env.PASSWORD");

        assertThatThrownBy(() -> EnvPlaceholders.validate("PLAINTEXT://${BROKER}", "env.KAFKA"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("env.KAFKA")
                .hasMessageContaining("${BROKER}");
        assertThatThrownBy(() -> EnvPlaceholders.validate("${host}", "env.KAFKA"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("未知占位符");
        assertThatThrownBy(() -> EnvPlaceholders.validate("PLAINTEXT://${HOST", "env.KAFKA"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("未闭合");
        assertThatThrownBy(() -> EnvPlaceholders.validate("${}", "env.KAFKA"))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("未知占位符");
    }
}
