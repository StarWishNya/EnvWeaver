package com.sentinel.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sentinel.monitoring.resource-usage 绑定。
 * 和 lifecycle、provisioning 写在同一个 sentinel 键下时不能互相覆盖。
 */
@DisplayName("资源采集配置绑定")
class ResourceUsagePropertiesBindingTest {

    @Test
    @DisplayName("默认 30 秒一轮、5 秒读超时，并且可以覆盖")
    void bindsIntervalAndTimeout() {
        ResourceUsageProperties defaults = new ResourceUsageProperties();
        assertThat(defaults.isEnabled()).isTrue();
        assertThat(defaults.getInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.getInterval().toMillis()).isEqualTo(30_000L);
        assertThat(defaults.getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.getMaxEnvironmentsPerPass()).isEqualTo(200);
        assertThat(defaults.getParallelism()).isEqualTo(4);

        Map<String, String> values = new LinkedHashMap<>();
        values.put("sentinel.monitoring.resource-usage.enabled", "false");
        values.put("sentinel.monitoring.resource-usage.interval", "24h");
        values.put("sentinel.monitoring.resource-usage.read-timeout", "5s");
        values.put("sentinel.monitoring.resource-usage.max-environments-per-pass", "15");
        values.put("sentinel.monitoring.resource-usage.parallelism", "2");

        ResourceUsageProperties properties = new ResourceUsageProperties();
        new Binder(new MapConfigurationPropertySource(values))
                .bind("sentinel.monitoring.resource-usage", Bindable.ofInstance(properties));

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getInterval()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getMaxEnvironmentsPerPass()).isEqualTo(15);
        assertThat(properties.getParallelism()).isEqualTo(2);

        Component component = ResourceUsageProperties.class.getAnnotation(Component.class);
        ConfigurationProperties configuration = ResourceUsageProperties.class.getAnnotation(ConfigurationProperties.class);
        assertThat(component.value()).isEqualTo("resourceUsageProperties");
        assertThat(configuration.prefix()).isEqualTo("sentinel.monitoring.resource-usage");
    }

    @Test
    @DisplayName("四个环境的 YAML 保留 lifecycle、provisioning 和 monitoring")
    void yamlKeepsMonitoringBesideOtherSentinelKeys() throws Exception {
        assertProfile("src/main/resources/application.yml", true, "30s");
        assertProfile("src/main/resources/application-dev.yml", true, "30s");
        assertProfile("src/main/resources/application-prod.yml", true, "30s");
        assertProfile("src/main/resources/application-test.yml", false, "24h");
    }

    private void assertProfile(String path, boolean enabled, String interval) throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(path, new FileSystemResource(Path.of(path)));
        assertThat(sources).isNotEmpty();
        PropertySource<?> source = sources.get(0);
        assertThat(source.getProperty("sentinel.lifecycle.auto-provision")).isNotNull();
        assertThat(source.getProperty("sentinel.lifecycle.reaper-interval")).isNotNull();
        assertThat(source.getProperty("sentinel.provisioning.ttl")).isNotNull();
        assertThat(source.getProperty("sentinel.provisioning.dependencies.mysql.image")).isEqualTo("mysql:8.0");
        assertThat(source.getProperty("sentinel.monitoring.resource-usage.enabled")).isEqualTo(enabled);
        assertThat(source.getProperty("sentinel.monitoring.resource-usage.interval")).isEqualTo(interval);
        assertThat(source.getProperty("sentinel.monitoring.resource-usage.read-timeout")).isEqualTo("5s");
        assertThat(String.valueOf(source.getProperty("sentinel.monitoring.resource-usage.max-environments-per-pass")))
                .isEqualTo("200");
        assertThat(String.valueOf(source.getProperty("sentinel.monitoring.resource-usage.parallelism")))
                .isEqualTo("4");
    }
}
