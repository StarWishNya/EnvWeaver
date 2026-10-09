package com.sentinel.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产配置里的 spring.data.redis 必须能绑定到 Boot 3.1 的 RedisProperties。
 */
@DisplayName("生产环境 Redis 配置绑定")
class ProdRedisPropertiesBindingTest {

    @Test
    @DisplayName("ssl.enabled 缺省为 false，且绑定 RedisProperties 不抛异常")
    void bindsRedisSslEnabledFalseByDefault() throws Exception {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application-prod", new ClassPathResource("application-prod.yml"));

        MutablePropertySources sources = new MutablePropertySources();
        Map<String, Object> required = new HashMap<>();
        required.put("REDIS_HOST", "redis.internal");
        required.put("REDIS_PASSWORD", "test-password");
        sources.addFirst(new MapPropertySource("required-placeholders", required));
        for (PropertySource<?> source : loaded) {
            sources.addLast(source);
        }

        Binder binder = new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));

        RedisProperties redis = binder.bind("spring.data.redis", Bindable.of(RedisProperties.class)).get();

        assertThat(redis.getSsl()).isNotNull();
        assertThat(redis.getSsl().isEnabled()).isFalse();
        assertThat(redis.getHost()).isEqualTo("redis.internal");
        assertThat(redis.getPort()).isEqualTo(6379);
    }
}
