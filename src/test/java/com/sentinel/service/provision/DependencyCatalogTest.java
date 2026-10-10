package com.sentinel.service.provision;

import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.enums.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 依赖解析与内置目录。
 */
@DisplayName("依赖目录")
class DependencyCatalogTest {

    private ProvisioningProperties properties;
    private DependencyCatalog catalog;

    @BeforeEach
    void setUp() {
        properties = new ProvisioningProperties();
        catalog = new DependencyCatalog(properties);
    }

    @Test
    @DisplayName("空依赖得到空列表")
    void emptyInputsResolveToNoDependencies() {
        assertThat(catalog.resolve(null)).isEmpty();
        assertThat(catalog.resolve("")).isEmpty();
        assertThat(catalog.resolve("   ")).isEmpty();
        assertThat(catalog.resolve("[]")).isEmpty();
        assertThat(catalog.resolve("null")).isEmpty();
        assertThat(catalog.resolve("null")).isEmpty();
        assertThat(catalog.resolve(" , , ")).isEmpty();
    }

    @Test
    @DisplayName("JSON 数组和 CSV 都按名称去重，忽略大小写和标签")
    void parsesJsonArrayAndCsv() {
        List<DependencySpec> fromJson = catalog.resolve("[\" mysql:8.0 \", \"REDIS\", \"mysql\"]");
        assertThat(fromJson).extracting(DependencySpec::getName).containsExactly("mysql", "redis");
        assertThat(fromJson.get(0).getImage()).isEqualTo("mysql:8.0");
        assertThat(fromJson.get(1).getImage()).isEqualTo("redis:7-alpine");

        List<DependencySpec> fromCsv = catalog.resolve("MySQL, redis:7.0, MYSQL");
        assertThat(fromCsv).extracting(DependencySpec::getName).containsExactly("mysql", "redis");

        List<DependencySpec> fromJsonString = catalog.resolve("\"postgres, nginx\"");
        assertThat(fromJsonString).extracting(DependencySpec::getName).containsExactly("postgres", "nginx");
    }

    @Test
    @DisplayName("内置目录包含约定的镜像、端口和环境变量")
    void builtinCatalogMatchesDefaults() {
        DependencySpec mysql = catalog.require("mysql");
        assertThat(mysql.getImage()).isEqualTo("mysql:8.0");
        assertThat(mysql.getContainerPort()).isEqualTo(3306);
        assertThat(mysql.getEnvironment())
                .containsEntry("MYSQL_DATABASE", "test")
                .containsEntry("MYSQL_ROOT_PASSWORD", DependencyCatalog.DEFAULT_PASSWORD);

        assertThat(catalog.require("redis").getImage()).isEqualTo("redis:7-alpine");
        assertThat(catalog.require("redis").getContainerPort()).isEqualTo(6379);
        assertThat(catalog.require("redis").getEnvironment()).isEmpty();

        DependencySpec postgres = catalog.require("postgres");
        assertThat(postgres.getImage()).isEqualTo("postgres:16-alpine");
        assertThat(postgres.getContainerPort()).isEqualTo(5432);
        assertThat(postgres.getEnvironment()).containsEntry("POSTGRES_PASSWORD", DependencyCatalog.DEFAULT_PASSWORD);

        assertThat(catalog.require("mongodb").getImage()).isEqualTo("mongo:7");
        assertThat(catalog.require("mongodb").getContainerPort()).isEqualTo(27017);
        assertThat(catalog.require("nginx").getImage()).isEqualTo("nginx:1.27-alpine");
        assertThat(catalog.require("nginx").getContainerPort()).isEqualTo(80);
        assertThat(catalog.require("kafka").getImage()).isEqualTo("apache/kafka:3.7.0");
        assertThat(catalog.require("kafka").getContainerPort()).isEqualTo(9092);
    }

    @Test
    @DisplayName("未知依赖在错误信息里点名")
    void unknownDependencyNamesTheToken() {
        BusinessException unknown = catchThrowableOfType(
                () -> catalog.resolve("[\"oracle\"]"), BusinessException.class);
        assertThat(unknown).isNotNull();
        assertThat(unknown.getMessage()).isEqualTo(
                "未知依赖: oracle，可选种类: mysql, redis, postgres, mongodb, nginx, kafka");
        assertThat(unknown.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.getCode());

        assertThatThrownBy(() -> catalog.resolve("mysql, etcd, Kafka:3"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未知依赖: etcd，可选种类: mysql, redis, postgres, mongodb, nginx, kafka; "
                        + "未知依赖版本: Kafka:3，可选版本: 3.7");
    }

    @Test
    @DisplayName("非法 JSON 和对象都拒绝")
    void rejectsMalformedDependencyPayloads() {
        assertThatThrownBy(() -> catalog.resolve("[mysql]"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JSON");
        assertThatThrownBy(() -> catalog.resolve("{\"mysql\":\"8.0\"}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JSON 数组");
        assertThatThrownBy(() -> catalog.resolve("[1]"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字符串数组");
    }

    @Test
    @DisplayName("配置可以覆盖内置镜像，也可以新增依赖")
    void configurationOverridesAndExtendsCatalog() {
        ProvisioningProperties.Dependency mysql = new ProvisioningProperties.Dependency();
        mysql.setImage("mysql:8.0.36");
        properties.getDependencies().put("mysql", mysql);

        ProvisioningProperties.Dependency kafka = new ProvisioningProperties.Dependency();
        kafka.setImage("bitnami/kafka:3.7");
        kafka.setPort(9092);
        properties.getDependencies().put("Kafka", kafka);

        ProvisioningProperties.Dependency memcached = new ProvisioningProperties.Dependency();
        memcached.setImage("memcached:1.6");
        memcached.setPort(11211);
        properties.getDependencies().put("memcached", memcached);

        DependencyCatalog overridden = new DependencyCatalog(properties);
        assertThat(overridden.require("mysql").getImage()).isEqualTo("mysql:8.0.36");
        assertThat(overridden.require("mysql").getContainerPort()).isEqualTo(3306);
        assertThat(overridden.require("mysql").getEnvironment()).containsEntry("MYSQL_DATABASE", "test");
        assertThat(overridden.resolve("kafka")).extracting(DependencySpec::getImage).containsExactly("bitnami/kafka:3.7");
        assertThat(overridden.resolve("mysql:8")).extracting(DependencySpec::getImage).containsExactly("mysql:8.0.36");
        assertThat(overridden.resolve("mysql:5.7")).extracting(DependencySpec::getImage).containsExactly("mysql:5.7");
        assertThat(overridden.resolve("memcached, mysql:5.7"))
                .extracting(DependencySpec::getName, DependencySpec::getImage)
                .containsExactly(
                        tuple("memcached", "memcached:1.6"),
                        tuple("mysql", "mysql:5.7"));
    }

    @Test
    @DisplayName("新增依赖缺少镜像或端口时配置无效")
    void incompleteCustomDependencyIsInvalid() {
        ProvisioningProperties.Dependency memcached = new ProvisioningProperties.Dependency();
        memcached.setImage("memcached:1.6");
        properties.getDependencies().put("memcached", memcached);

        assertThatThrownBy(() -> new DependencyCatalog(properties))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("memcached");
    }
}
