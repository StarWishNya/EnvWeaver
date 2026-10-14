package com.sentinel.config;

import com.sentinel.config.properties.JwtProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT 密钥在启动时由 @Validated 约束拒绝空值、过短和未解析占位符。
 */
@DisplayName("JWT 密钥校验")
class JwtPropertiesValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("拒绝空密钥")
    void rejectsBlankSecret() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("   ");

        Set<String> messages = messages(properties);

        assertThat(messages).isNotEmpty();
        assertThat(messages).anyMatch(message -> message.contains("JWT 密钥"));
    }

    @Test
    @DisplayName("拒绝短于 32 个字符的密钥")
    void rejectsShortSecret() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("1234567890123456789012345678901");

        Set<String> messages = messages(properties);

        assertThat(messages).anyMatch(message -> message.contains("32"));
        assertThat(messages).noneMatch(message -> message.contains("占位符"));
    }

    @Test
    @DisplayName("拒绝未解析的 ${...} 密钥")
    void rejectsUnresolvedPlaceholder() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("${JWT_SECRET}01234567890123456789");

        Set<String> messages = messages(properties);

        assertThat(messages).anyMatch(message -> message.contains("占位符"));
    }

    @Test
    @DisplayName("接受不少于 32 个字符且已解析的密钥")
    void acceptsResolvedSecret() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef");

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("主配置不再提供 JWT 密钥默认值，并导入 .env")
    void applicationConfigRequiresJwtSecretFromEnvironment() throws IOException {
        String application = readClasspath("application.yml");
        String dev = readClasspath("application-dev.yml");

        assertThat(application).contains("secret: ${JWT_SECRET}");
        assertThat(application).contains("optional:file:.env[.properties]");
        assertThat(application).doesNotContain("your-secret-key-change-in-production");
        assertThat(dev).doesNotContain("sentinel-dev-secret-key-2024-change-in-production");
        assertThat(dev).doesNotContain("secret:");
    }

    private static Set<String> messages(JwtProperties properties) {
        return validator.validate(properties).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    private static String readClasspath(String name) throws IOException {
        try (InputStream in = JwtPropertiesValidationTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
