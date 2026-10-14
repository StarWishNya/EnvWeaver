package com.sentinel.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * schema.sql / data.sql 中的种子口令必须是真实 BCrypt 哈希。
 */
@DisplayName("种子用户密码哈希")
class SeedPasswordHashTest {

    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}");

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("schema.sql 与 data.sql 的 admin 哈希匹配 admin123")
    void adminHashMatchesAdmin123() throws IOException {
        String schemaHash = hashNear(read("schema.sql"), "admin");
        String dataHash = hashNear(read("data.sql"), "admin");

        assertThat(encoder.matches("admin123", schemaHash)).isTrue();
        assertThat(encoder.matches("admin123", dataHash)).isTrue();
        assertThat(encoder.matches("user123", schemaHash)).isFalse();
    }

    @Test
    @DisplayName("data.sql 的 user 哈希匹配 user123")
    void userHashMatchesUser123() throws IOException {
        String dataHash = hashNear(read("data.sql"), "user");

        assertThat(encoder.matches("user123", dataHash)).isTrue();
        assertThat(encoder.matches("admin123", dataHash)).isFalse();
    }

    private static String read(String classpathName) throws IOException {
        try (InputStream in = SeedPasswordHashTest.class.getClassLoader().getResourceAsStream(classpathName)) {
            assertThat(in).as(classpathName).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String hashNear(String sql, String username) {
        int index = sql.indexOf("'" + username + "'");
        assertThat(index).as("username %s", username).isGreaterThanOrEqualTo(0);
        Matcher matcher = BCRYPT.matcher(sql.substring(index));
        assertThat(matcher.find()).as("bcrypt hash for %s", username).isTrue();
        return matcher.group();
    }
}
