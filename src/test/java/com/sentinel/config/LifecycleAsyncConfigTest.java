package com.sentinel.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sentinel.config.properties.LifecycleProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 生命周期线程池与配置绑定。不连接数据库。
 */
@DisplayName("生命周期配置")
class LifecycleAsyncConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableLifecycleProperties.class);

    @Test
    @DisplayName("默认值与 application.yml 中的时长一致")
    void shouldExposeDefaults() {
        LifecycleProperties properties = new LifecycleProperties();
        assertThat(properties.isAutoProvision()).isTrue();
        assertThat(properties.isAutoDiagnose()).isTrue();
        assertThat(properties.getFailedRetention()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.getPendingSweepInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getReaperInterval()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("能绑定 sentinel.lifecycle，测试配置关闭自动供给和诊断")
    void shouldBindPropertiesAndKeepTestProfileOff() throws Exception {
        runner.withPropertyValues(
                "sentinel.lifecycle.auto-provision=false",
                "sentinel.lifecycle.failed-retention=15m"
        ).run(context -> {
            LifecycleProperties properties = context.getBean(LifecycleProperties.class);
            assertThat(properties.isAutoProvision()).isFalse();
            assertThat(properties.isAutoDiagnose()).isTrue();
            assertThat(properties.getFailedRetention()).isEqualTo(Duration.ofMinutes(15));
        });

        String main = readResource("application.yml");
        String test = readResource("application-test.yml");
        assertThat(main).contains("auto-provision: true").contains("failed-retention: 30m");
        assertThat(test).contains("auto-provision: false").contains("auto-diagnose: false");
        assertThat(lifecycleBlock(main)).doesNotContain("ttl");
        assertThat(lifecycleBlock(test)).doesNotContain("ttl");
    }

    @Test
    @DisplayName("线程池有界，拒绝时记警告且不抛出")
    void shouldWarnAndDiscardWhenExecutorRejects() {
        ThreadPoolTaskExecutor executor = new LifecycleAsyncConfig().lifecycleExecutor();
        executor.initialize();
        Logger logger = (Logger) LoggerFactory.getLogger(LifecycleAsyncConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getQueueCapacity()).isEqualTo(100);
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            assertThatCode(() -> pool.getRejectedExecutionHandler().rejectedExecution(() -> {
            }, pool)).doesNotThrowAnyException();
            assertThat(appender.list)
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("留给扫尾"));
        } finally {
            logger.detachAppender(appender);
            executor.shutdown();
        }
    }

    private static String lifecycleBlock(String yaml) {
        int start = yaml.indexOf("lifecycle:");
        int end = yaml.indexOf("provisioning:", start);
        assertThat(start).isNotNegative();
        assertThat(end).isGreaterThan(start);
        return yaml.substring(start, end);
    }

    private static String readResource(String name) throws Exception {
        try (var input = LifecycleAsyncConfigTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(input).as(name).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Configuration
    @EnableConfigurationProperties(LifecycleProperties.class)
    static class EnableLifecycleProperties {
    }
}
