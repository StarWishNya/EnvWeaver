package com.sentinel.config.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sentinel.provisioning 绑定，以及和 lifecycle 写在同一个 sentinel 键下时不会互相覆盖。
 */
@DisplayName("供给配置绑定")
class ProvisioningPropertiesBindingTest {

    @Test
    @DisplayName("时长、端口区间和依赖环境变量可以绑定，密码不进入 toString")
    void bindsProvisioningPropertiesWithoutExposingPassword() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sentinel.provisioning.ttl", "2h");
        values.put("sentinel.provisioning.port-range", "30000-39999");
        values.put("sentinel.provisioning.pull-missing", "true");
        values.put("sentinel.provisioning.pull-timeout", "5m");
        values.put("sentinel.provisioning.ready-timeout", "90s");
        values.put("sentinel.provisioning.host", "localhost");
        values.put("sentinel.provisioning.dependencies.mysql.image", "mysql:8.0.36");
        values.put("sentinel.provisioning.dependencies.mysql.port", "3306");
        values.put("sentinel.provisioning.dependencies.mysql.env.MYSQL_DATABASE", "test");
        values.put("sentinel.provisioning.dependencies.mysql.env.MYSQL_ROOT_PASSWORD", "sentinel-secret-value");

        ProvisioningProperties properties = new ProvisioningProperties();
        new Binder(new MapConfigurationPropertySource(values))
                .bind("sentinel.provisioning", Bindable.ofInstance(properties));

        assertThat(properties.getTtl()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.getPullTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getReadyTimeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(properties.isPullMissing()).isTrue();
        assertThat(properties.getPortRange()).isEqualTo("30000-39999");
        assertThat(properties.getHost()).isEqualTo("localhost");
        assertThat(properties.getDependencies().get("mysql").getImage()).isEqualTo("mysql:8.0.36");
        assertThat(properties.getDependencies().get("mysql").getEnv()).containsEntry("MYSQL_DATABASE", "test");
        assertThat(properties.toString()).doesNotContain("sentinel-secret-value");
    }

    @Test
    @DisplayName("四个环境的 YAML 同时保留 lifecycle 和 provisioning")
    void yamlKeepsLifecycleAndProvisioning() throws Exception {
        assertProfile("src/main/resources/application.yml", true);
        assertProfile("src/main/resources/application-dev.yml", true);
        assertProfile("src/main/resources/application-test.yml", false);
        assertProfile("src/main/resources/application-prod.yml", true);
    }

    private void assertProfile(String path, boolean autoProvision) throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(path, new FileSystemResource(Path.of(path)));
        assertThat(sources).isNotEmpty();
        PropertySource<?> source = sources.get(0);
        assertThat(source.getProperty("sentinel.lifecycle.auto-provision")).isEqualTo(autoProvision);
        assertThat(source.getProperty("sentinel.lifecycle.auto-diagnose")).isEqualTo(autoProvision);
        assertThat(source.getProperty("sentinel.provisioning.ttl")).isNotNull();
        assertThat(String.valueOf(source.getProperty("sentinel.provisioning.port-range"))).contains("30000");
        assertThat(source.getProperty("sentinel.provisioning.dependencies.mysql.image")).isNull();
        assertThat(source.getProperty("sentinel.provisioning.dependencies.nginx.image")).isNull();
        assertThat(source.getProperty("sentinel.provisioning.dependencies.rabbitmq.image")).isNull();
    }
}
