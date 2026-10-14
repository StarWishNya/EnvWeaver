package com.sentinel.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开发配置不应把 SQL 结果行（含密码哈希）打到标准输出，
 * 健康检查也不应向匿名调用方展开组件详情。
 */
@DisplayName("开发与监控配置")
class DevProfileSafetyTest {

    @Test
    @DisplayName("开发环境不用 StdOutImpl 打印 SQL，也不把 MyBatis 开到 DEBUG")
    void devProfileDoesNotDumpSqlRows() throws IOException {
        String dev = read("application-dev.yml");

        assertThat(dev).doesNotContain("StdOutImpl");
        assertThat(dev).doesNotContain("com.baomidou.mybatisplus: DEBUG");
        assertThat(dev).contains("com.sentinel.mapper: INFO");
    }

    @Test
    @DisplayName("匿名健康检查不展开组件详情")
    void healthDetailsRequireAuthorization() throws IOException {
        String main = read("application.yml");

        assertThat(main).contains("show-details: when-authorized");
        assertThat(main).doesNotContain("show-details: always");
    }

    private static String read(String classpathName) throws IOException {
        try (InputStream in = DevProfileSafetyTest.class.getClassLoader().getResourceAsStream(classpathName)) {
            assertThat(in).as(classpathName).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
