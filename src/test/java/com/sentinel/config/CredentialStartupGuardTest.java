package com.sentinel.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sentinel.config.properties.JwtProperties;
import com.sentinel.config.properties.SentinelSecurityProperties;
import com.sentinel.domain.User;
import com.sentinel.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 启动凭据检查：示例密钥、低熵密钥、种子默认口令，以及 admin 口令引导。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("启动凭据检查")
class CredentialStartupGuardTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final String GOOD_SECRET = "kR7mN2pQ9vL4xT8wY3uA6bC1dE5fH0jZ";
    private static final String ADMIN_PASSWORD = "correct-horse-battery";

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    private JwtProperties jwtProperties;
    private SentinelSecurityProperties securityProperties;
    private CredentialStartupGuard guard;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret(GOOD_SECRET);
        securityProperties = new SentinelSecurityProperties();
        guard = new CredentialStartupGuard(jwtProperties, securityProperties, userMapper, ENCODER);
        logger = (Logger) LoggerFactory.getLogger(CredentialStartupGuard.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "change-in-production",
            "your-super-secret",
            "changeme",
            "change_me"
    })
    @DisplayName("拒绝包含占位标记的密钥，日志文本不含密钥本身")
    void rejectsForbiddenMarker(String marker) {
        String secret = "Aa1-" + marker + "-Zz8901234567";

        List<String> problems = CredentialStartupGuard.jwtProblems(secret);

        assertThat(problems).anyMatch(problem -> problem.contains("「" + marker + "」"));
        assertThat(problems).noneMatch(problem -> problem.contains(secret));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "change-in-production",
            "your-super-secret",
            "changeme",
            "change_me"
    })
    @DisplayName("占位标记忽略大小写")
    void rejectsForbiddenMarkerIgnoringCase(String marker) {
        String secret = "Aa1-" + marker.toUpperCase(Locale.ROOT) + "-Zz8901234567";

        List<String> problems = CredentialStartupGuard.jwtProblems(secret);

        assertThat(problems).anyMatch(problem -> problem.contains("「" + marker + "」"));
        assertThat(problems).noneMatch(problem -> problem.contains(secret));
    }

    @Test
    @DisplayName("拒绝旧的 .env.example 示例密钥")
    void rejectsLegacyExampleSecret() {
        String secret = "your-super-secret-jwt-key-change-in-production-min-32-chars";

        List<String> problems = CredentialStartupGuard.jwtProblems(secret);

        assertThat(joined(problems)).contains("示例值");
        assertThat(joined(problems)).contains("「your-super-secret」");
        assertThat(joined(problems)).contains("「change-in-production」");
        assertThat(joined(problems)).doesNotContain(secret);
    }

    @Test
    @DisplayName("拒绝文档里不带 min-32 后缀的示例密钥")
    void rejectsShorterLegacyExampleSecret() {
        String secret = "your-super-secret-jwt-key-change-in-production";

        assertThat(joined(CredentialStartupGuard.jwtProblems(secret))).contains("示例值");
    }

    @Test
    @DisplayName(".env.example 的 JWT_SECRET 带 change_me，检查会拒绝")
    void rejectsCurrentEnvExampleSecret() throws IOException {
        String text = Files.readString(Path.of(".env.example"));
        Matcher matcher = Pattern.compile("(?m)^JWT_SECRET=(.*)$").matcher(text);
        assertThat(matcher.find()).isTrue();
        String secret = matcher.group(1).trim();

        assertThat(secret).isEqualTo(CredentialStartupGuard.ENV_EXAMPLE_JWT_SECRET);
        assertThat(secret).contains("change_me");
        assertThat(joined(CredentialStartupGuard.jwtProblems(secret))).contains("示例值").contains("「change_me」");
        assertThat(text.lines().noneMatch(line -> line.startsWith("SENTINEL_ADMIN_PASSWORD="))).isTrue();
        assertThat(text).contains("# SENTINEL_ADMIN_PASSWORD=");
    }

    @Test
    @DisplayName("不同字符少于 8 个的密钥被拒绝")
    void rejectsLowEntropySecret() {
        String secret = "abcdefgabcdefgabcdefgabcdefgabcd";

        List<String> problems = CredentialStartupGuard.jwtProblems(secret);

        assertThat(secret.codePoints().distinct().count()).isEqualTo(7);
        assertThat(joined(problems)).contains("少于 8");
        assertThat(joined(problems)).doesNotContain(secret);
    }

    @Test
    @DisplayName("恰好 8 个不同字符且没有占位标记时通过")
    void acceptsEightDistinctCharacters() {
        String secret = "abcdefghabcdefghabcdefghabcdefgh";

        assertThat(secret.codePoints().distinct().count()).isEqualTo(8);
        assertThat(CredentialStartupGuard.jwtProblems(secret)).isEmpty();
    }

    @Test
    @DisplayName("随机高熵密钥通过")
    void acceptsGoodSecret() {
        assertThat(GOOD_SECRET).hasSizeGreaterThanOrEqualTo(32);
        assertThat(GOOD_SECRET.codePoints().distinct().count()).isGreaterThanOrEqualTo(8);
        assertThat(CredentialStartupGuard.jwtProblems(GOOD_SECRET)).isEmpty();
    }

    @Test
    @DisplayName("空密钥单独报告，不再叠加低熵")
    void rejectsBlankSecret() {
        assertThat(CredentialStartupGuard.jwtProblems(null)).singleElement()
                .asString().contains("JWT 密钥为空");
        assertThat(CredentialStartupGuard.jwtProblems("   ")).singleElement()
                .asString().contains("JWT 密钥为空");
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin123", "user123", "admin", "password", "123456"})
    @DisplayName("启用用户的哈希能对上已知默认口令")
    void detectsEachDefaultPassword(String rawPassword) {
        User user = user("bob", ENCODER.encode(rawPassword), true);

        List<String> problems = CredentialStartupGuard.defaultPasswordProblems(List.of(user), ENCODER);

        assertThat(problems).singleElement().asString()
                .contains("启用用户 bob")
                .contains("默认密码")
                .contains(rawPassword)
                .doesNotContain(user.getPassword());
    }

    @Test
    @DisplayName("强口令、禁用用户、空哈希和空列表都不算问题")
    void ignoresStrongDisabledAndBlankPasswords() {
        User strong = user("admin", ENCODER.encode("correct-horse-battery-staple"), true);
        User disabled = user("user", ENCODER.encode("admin123"), false);
        User blank = user("blank", "  ", true);
        User missing = user("missing", null, true);

        assertThat(CredentialStartupGuard.defaultPasswordProblems(
                List.of(strong, disabled, blank, missing), ENCODER)).isEmpty();
        assertThat(CredentialStartupGuard.defaultPasswordProblems(null, ENCODER)).isEmpty();
        assertThat(CredentialStartupGuard.defaultPasswordProblems(List.of(), ENCODER)).isEmpty();
        assertThat(CredentialStartupGuard.defaultPasswordProblems(Arrays.asList(strong, null), ENCODER)).isEmpty();
    }

    @Test
    @DisplayName("enabled 为空时仍检查，避免漏掉未映射的启用位")
    void checksUserWhenEnabledFlagMissing() {
        User user = user("admin", ENCODER.encode("admin123"), null);

        assertThat(CredentialStartupGuard.defaultPasswordProblems(List.of(user), ENCODER))
                .singleElement().asString().contains("启用用户 admin");
    }

    @Test
    @DisplayName("fail 模式在默认口令上拒绝启动，异常信息点名 admin 且不含哈希")
    void failModeAbortsOnDefaultAdminPassword() {
        User admin = user("admin", ENCODER.encode("admin123"), true);
        when(userMapper.selectList(any())).thenReturn(List.of(admin));
        securityProperties.setCredentialCheck("fail");

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝启动")
                .hasMessageContaining("启用用户 admin")
                .hasMessageContaining("默认密码")
                .hasMessageContaining("admin123")
                .hasMessageContaining("SENTINEL_ADMIN_PASSWORD")
                .hasMessageNotContaining(admin.getPassword());
        assertThat(formattedLogs()).anyMatch(line -> line.contains("拒绝启动"));
        assertThat(formattedLogs()).noneMatch(line -> line.contains(admin.getPassword()));
    }

    @Test
    @DisplayName("fail 模式同时列出密钥和默认口令")
    void failModeListsEveryProblem() {
        jwtProperties.setSecret("Aa1-change_me-Zz8901234567");
        User user = user("user", ENCODER.encode("user123"), true);
        when(userMapper.selectList(any())).thenReturn(List.of(user));
        securityProperties.setCredentialCheck("FAIL");

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("「change_me」")
                .hasMessageContaining("启用用户 user")
                .hasMessageNotContaining(jwtProperties.getSecret())
                .hasMessageNotContaining(user.getPassword());
    }

    @Test
    @DisplayName("密钥和口令都合格时 fail 模式放行")
    void failModePassesCleanCredentials() {
        when(userMapper.selectList(any())).thenReturn(List.of(
                user("admin", ENCODER.encode("correct-horse-battery-staple"), true)));
        securityProperties.setCredentialCheck("fail");

        guard.run(null);

        assertThat(formattedLogs()).noneMatch(line -> line.contains("凭据安全检查"));
    }

    @Test
    @DisplayName("warn 模式每个问题一条 WARN，不中断，不记录密钥和哈希")
    void warnModeLogsEachProblemWithoutAborting() {
        jwtProperties.setSecret("Aa1-changeme-Zz8901234567890");
        User admin = user("admin", ENCODER.encode("password"), true);
        when(userMapper.selectList(any())).thenReturn(List.of(admin));
        securityProperties.setCredentialCheck("warn");

        guard.run(null);

        List<String> warnings = formattedLogs().stream()
                .filter(line -> line.contains("【凭据安全检查】"))
                .toList();
        assertThat(warnings).hasSize(2);
        assertThat(warnings).anyMatch(line -> line.contains("「changeme」"));
        assertThat(warnings).anyMatch(line -> line.contains("启用用户 admin") && line.contains("默认密码"));
        assertThat(warnings).noneMatch(line -> line.contains(jwtProperties.getSecret()) || line.contains(admin.getPassword()));
        assertThat(logs.list).filteredOn(event -> event.getFormattedMessage().contains("【凭据安全检查】"))
                .allMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    @DisplayName("warn 模式没有问题时不打凭据日志")
    void warnModeIsQuietWhenClean() {
        when(userMapper.selectList(any())).thenReturn(List.of());
        securityProperties.setCredentialCheck("  warn  ");

        guard.run(null);

        assertThat(formattedLogs()).noneMatch(line -> line.contains("凭据安全检查") || line.contains("【凭据安全检查】"));
    }

    @Test
    @DisplayName("未配置模式时按 warn 处理")
    void blankModeDefaultsToWarn() {
        jwtProperties.setSecret("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        when(userMapper.selectList(any())).thenReturn(List.of());
        securityProperties.setCredentialCheck(null);

        guard.run(null);

        assertThat(formattedLogs()).anyMatch(line -> line.contains("少于 8"));
    }

    @Test
    @DisplayName("off 模式不查用户、不打凭据日志")
    void offModeSkipsChecks() {
        jwtProperties.setSecret("Aa1-change_me-Zz8901234567");
        securityProperties.setCredentialCheck("off");

        guard.run(null);

        verifyNoInteractions(userMapper);
        assertThat(formattedLogs()).noneMatch(line -> line.contains("凭据") || line.contains("change_me"));
    }

    @Test
    @DisplayName("非法模式拒绝启动，且不访问数据库")
    void invalidModeAborts() {
        securityProperties.setCredentialCheck("nope");

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail、warn 或 off");
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("管理员口令短于 12 个字符时拒绝启动，不写库也不把口令打进日志")
    void shortAdminPasswordAborts() {
        securityProperties.setCredentialCheck("off");
        securityProperties.setAdminPassword("abcdefghijk");
        CredentialStartupGuard mocked = guardWithMockEncoder();

        assertThatThrownBy(() -> mocked.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SENTINEL_ADMIN_PASSWORD")
                .hasMessageContaining("12")
                .hasMessageNotContaining("abcdefghijk");
        verifyNoInteractions(userMapper);
        verify(passwordEncoder, never()).encode(any());
        assertThat(formattedLogs()).noneMatch(line -> line.contains("abcdefghijk"));
    }

    @Test
    @DisplayName("warn 模式下过短口令仍然拒绝启动，并带上其他凭据问题")
    void shortAdminPasswordAbortsEvenInWarnMode() {
        jwtProperties.setSecret("Aa1-change_me-Zz8901234567");
        when(userMapper.selectList(any())).thenReturn(List.of());
        securityProperties.setCredentialCheck("warn");
        securityProperties.setAdminPassword("short-pass1");
        CredentialStartupGuard mocked = guardWithMockEncoder();

        assertThatThrownBy(() -> mocked.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("12")
                .hasMessageContaining("「change_me」")
                .hasMessageNotContaining("short-pass1");
        verify(passwordEncoder, never()).encode(any());
        verify(userMapper, never()).insert(any(User.class));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("空白管理员口令视为未设置")
    void blankAdminPasswordIsIgnored() {
        securityProperties.setCredentialCheck("off");
        securityProperties.setAdminPassword("   ");

        guard.run(null);

        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("没有 admin 时按 ADMIN 角色创建")
    void bootstrapCreatesAdmin() {
        securityProperties.setCredentialCheck("off");
        securityProperties.setAdminPassword("  short-pass12  ");
        when(passwordEncoder.encode("short-pass12")).thenReturn("encoded-hash");
        when(userMapper.findByUsername("admin")).thenReturn(null);
        CredentialStartupGuard mocked = guardWithMockEncoder();

        mocked.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        verify(userMapper, never()).updateById(any(User.class));
        verify(userMapper, never()).selectList(any());
        User created = captor.getValue();
        assertThat(created.getUsername()).isEqualTo("admin");
        assertThat(created.getRole()).isEqualTo("ADMIN");
        assertThat(created.getEnabled()).isTrue();
        assertThat(created.getEmail()).isEqualTo("admin@sentinel.local");
        assertThat(created.getPassword()).isEqualTo("encoded-hash");
        assertThat(formattedLogs()).anyMatch(line -> line.contains("创建管理员用户 admin"));
        assertThat(formattedLogs()).noneMatch(line -> line.contains("short-pass12") || line.contains("encoded-hash"));
    }

    @Test
    @DisplayName("已有 admin 时只更新密码，不改角色和启用状态")
    void bootstrapUpdatesAdminPasswordOnly() {
        User existing = user("admin", "old-hash", false);
        existing.setId(7L);
        existing.setRole("USER");
        existing.setEmail("kept@example.com");
        securityProperties.setCredentialCheck("off");
        securityProperties.setAdminPassword(ADMIN_PASSWORD);
        when(passwordEncoder.encode(ADMIN_PASSWORD)).thenReturn("new-hash");
        when(userMapper.findByUsername("admin")).thenReturn(existing);
        CredentialStartupGuard mocked = guardWithMockEncoder();

        mocked.run(null);

        verify(userMapper).updateById(existing);
        verify(userMapper, never()).insert(any(User.class));
        assertThat(existing.getPassword()).isEqualTo("new-hash");
        assertThat(existing.getRole()).isEqualTo("USER");
        assertThat(existing.getEnabled()).isFalse();
        assertThat(existing.getEmail()).isEqualTo("kept@example.com");
        assertThat(formattedLogs()).noneMatch(line -> line.contains(ADMIN_PASSWORD) || line.contains("new-hash"));
    }

    @Test
    @DisplayName("off 模式仍会按新口令更新 admin，但不扫描默认口令")
    void offModeStillBootstrapsAdmin() {
        User existing = user("admin", ENCODER.encode("admin123"), true);
        securityProperties.setCredentialCheck("off");
        securityProperties.setAdminPassword(ADMIN_PASSWORD);
        jwtProperties.setSecret("Aa1-change_me-Zz8901234567");
        when(userMapper.findByUsername("admin")).thenReturn(existing);

        guard.run(null);

        verify(userMapper, never()).selectList(any());
        assertThat(ENCODER.matches(ADMIN_PASSWORD, existing.getPassword())).isTrue();
        assertThat(ENCODER.matches("admin123", existing.getPassword())).isFalse();
    }

    @Test
    @DisplayName("fail 模式下先换成合格口令，再检查，不再把种子哈希当成默认口令")
    void failModeAcceptsBootstrappedAdmin() {
        User admin = user("admin", ENCODER.encode("admin123"), true);
        when(userMapper.findByUsername("admin")).thenReturn(admin);
        when(userMapper.selectList(any())).thenReturn(List.of(admin));
        securityProperties.setCredentialCheck("fail");
        securityProperties.setAdminPassword(ADMIN_PASSWORD);

        guard.run(null);

        assertThat(ENCODER.matches(ADMIN_PASSWORD, admin.getPassword())).isTrue();
        verify(userMapper).updateById(admin);
    }

    @Test
    @DisplayName("属性对象的 toString 不包含管理员口令")
    void propertiesToStringOmitsAdminPassword() {
        securityProperties.setAdminPassword("SuperSecretAdminPassword");

        assertThat(securityProperties.toString()).doesNotContain("SuperSecretAdminPassword");
    }

    @Test
    @DisplayName("各 profile 的检查开关和 README 说明一致")
    void profileDefaultsAndReadme() throws IOException {
        assertThat(read("application.yml")).contains("credential-check: \"warn\"");
        assertThat(read("application.yml")).contains("admin-password: \"${SENTINEL_ADMIN_PASSWORD:}\"");
        assertThat(read("application-dev.yml")).contains("credential-check: \"warn\"");
        assertThat(read("application-dev.yml")).doesNotContain("secret:");
        assertThat(read("application-prod.yml")).contains("credential-check: \"fail\"");
        assertThat(read("application-test.yml")).contains("credential-check: \"off\"");
        assertThat(read("config/application-test.yml")).contains("credential-check: \"off\"");

        String readme = Files.readString(Path.of("README.md"));
        assertThat(readme).contains("首次登录与生产检查清单");
        assertThat(readme).contains("SENTINEL_ADMIN_PASSWORD");
        assertThat(readme).contains("credential-check");
    }

    private CredentialStartupGuard guardWithMockEncoder() {
        return new CredentialStartupGuard(jwtProperties, securityProperties, userMapper, passwordEncoder);
    }

    private List<String> formattedLogs() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String joined(List<String> problems) {
        return String.join("\n", problems);
    }

    private static User user(String username, String password, Boolean enabled) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        user.setEnabled(enabled);
        return user;
    }

    private static String read(String classpathName) throws IOException {
        try (InputStream in = CredentialStartupGuardTest.class.getClassLoader().getResourceAsStream(classpathName)) {
            assertThat(in).as(classpathName).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
