package com.sentinel.service.provision;

import com.sentinel.config.BusinessException;
import com.sentinel.enums.ErrorCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 种类、默认版本和别名解析。
 */
@DisplayName("中间件解析")
class MiddlewareResolverTest {

    private static MiddlewareCatalog catalog;

    @BeforeAll
    static void load() {
        catalog = MiddlewareCatalog.load();
    }

    @Test
    @DisplayName("不写版本时用默认版本")
    void bareKindUsesDefault() {
        List<ResolvedDependency> resolved = MiddlewareResolver.resolve(catalog, List.of("mysql", " REDIS "));

        assertThat(resolved).extracting(ResolvedDependency::getKind).containsExactly("mysql", "redis");
        assertThat(resolved.get(0).getVersion()).isEqualTo("8.0");
        assertThat(resolved.get(0).getCatalogVersion().getImage()).isEqualTo("mysql:8.0");
        assertThat(resolved.get(1).getVersion()).isEqualTo("7");
        assertThat(resolved.get(1).getCatalogVersion().getImage()).isEqualTo("redis:7-alpine");
    }

    @Test
    @DisplayName("别名解析到目录版本，同一种类以后写的为准")
    void aliasResolvesAndLastKindWins() {
        List<ResolvedDependency> resolved = MiddlewareResolver.resolve(
                catalog, List.of("mysql:5.7", "mysql:8", "redis:7.2"));

        assertThat(resolved).extracting(ResolvedDependency::getVersion).containsExactly("8.0", "7");
        assertThat(resolved.get(0).canonical()).isEqualTo("mysql:8.0");
        assertThat(resolved.get(1).getCatalogVersion().getImage()).isEqualTo("redis:7-alpine");
    }

    @Test
    @DisplayName("未知版本返回可选版本")
    void unknownVersionListsCandidates() {
        BusinessException failure = catchThrowableOfType(
                () -> MiddlewareResolver.resolve(catalog, List.of("mysql:9.9")), BusinessException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.getCode());
        assertThat(failure.getMessage()).isEqualTo("未知依赖版本: mysql:9.9，可选版本: 5.7, 8.0");
        assertThat(data(failure).get("code")).isEqualTo(ResolveFailure.UNKNOWN_VERSION);
        assertThat(candidates(failure)).containsExactly("5.7", "8.0");
    }

    @Test
    @DisplayName("未知种类返回可选种类")
    void unknownKindListsCandidates() {
        BusinessException failure = catchThrowableOfType(
                () -> MiddlewareResolver.resolve(catalog, List.of("oracle")), BusinessException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.getCode());
        assertThat(failure.getMessage()).contains("未知依赖: oracle").contains("mysql").contains("kafka");
        assertThat(data(failure).get("code")).isEqualTo(ResolveFailure.UNKNOWN_KIND);
        assertThat(candidates(failure)).contains("mysql", "redis", "postgres", "mongodb", "nginx", "kafka")
                .doesNotContain("oracle");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(BusinessException failure) {
        return (Map<String, Object>) failure.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<String> candidates(BusinessException failure) {
        return (List<String>) data(failure).get("candidates");
    }
}
