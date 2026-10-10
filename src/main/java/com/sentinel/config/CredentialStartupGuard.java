package com.sentinel.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sentinel.config.properties.JwtProperties;
import com.sentinel.config.properties.SentinelSecurityProperties;
import com.sentinel.domain.User;
import com.sentinel.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 启动时拒绝示例 JWT 密钥和种子默认口令。
 * 生产配置为 fail（中止启动），开发与默认配置为 warn，测试配置为 off。
 * 日志只描述哪一项不合格以及如何修改，不记录密钥、新口令或密码哈希。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CredentialStartupGuard implements ApplicationRunner {

    static final int MIN_ADMIN_PASSWORD_LENGTH = 12;
    static final int MIN_DISTINCT_CHARACTERS = 8;
    static final String ADMIN_USERNAME = "admin";

    /**
     * 与 .env.example 中的 JWT_SECRET 保持一致。该示例故意带 change_me，启动检查会拒绝它。
     */
    static final String ENV_EXAMPLE_JWT_SECRET = "change_me_replace_this_jwt_secret_min_32_characters";

    static final List<String> KNOWN_EXAMPLE_SECRETS = List.of(
            "your-super-secret-jwt-key-change-in-production-min-32-chars",
            "your-super-secret-jwt-key-change-in-production",
            ENV_EXAMPLE_JWT_SECRET
    );

    static final List<String> FORBIDDEN_MARKERS = List.of(
            "change-in-production",
            "your-super-secret",
            "changeme",
            "change_me"
    );

    static final List<String> DEFAULT_PASSWORDS = List.of(
            "admin123",
            "user123",
            "admin",
            "password",
            "123456"
    );

    private final JwtProperties jwtProperties;
    private final SentinelSecurityProperties securityProperties;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public CredentialStartupGuard(JwtProperties jwtProperties,
                                  SentinelSecurityProperties securityProperties,
                                  UserMapper userMapper,
                                  PasswordEncoder passwordEncoder) {
        this.jwtProperties = jwtProperties;
        this.securityProperties = securityProperties;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        CheckMode mode = resolveMode(securityProperties.getCredentialCheck());
        List<String> fatal = new ArrayList<>();
        // 合法的新口令先写入，再检查库里的哈希。已知默认口令都短于 12，新口令不会被误判为默认口令。
        applyAdminPassword(fatal);
        List<String> problems = new ArrayList<>(fatal);
        if (mode != CheckMode.OFF) {
            problems.addAll(jwtProblems(jwtProperties.getSecret()));
            problems.addAll(defaultPasswordProblems(loadEnabledUsers(), passwordEncoder));
        }
        if (!fatal.isEmpty() || (mode == CheckMode.FAIL && !problems.isEmpty())) {
            String report = failureReport(problems);
            log.error("{}", report);
            throw new IllegalStateException(report);
        }
        if (mode == CheckMode.WARN) {
            for (String problem : problems) {
                log.warn("【凭据安全检查】模式为 warn，启动继续。{}", problem);
            }
        }
    }

    /**
     * 未配置口令时什么也不做。短于 12 个字符只记入 fatal，不写数据库。
     */
    private void applyAdminPassword(List<String> fatal) {
        String configured = securityProperties.getAdminPassword();
        if (!StringUtils.hasText(configured)) {
            return;
        }
        String password = configured.trim();
        if (password.length() < MIN_ADMIN_PASSWORD_LENGTH) {
            fatal.add("环境变量 SENTINEL_ADMIN_PASSWORD（配置项 sentinel.security.admin-password）"
                    + "长度不足 12 个字符。请改为至少 12 个字符的随机口令后重启，不要使用示例口令。");
            return;
        }
        String hash = passwordEncoder.encode(password);
        User admin = userMapper.findByUsername(ADMIN_USERNAME);
        if (admin == null) {
            User created = new User();
            created.setUsername(ADMIN_USERNAME);
            created.setPassword(hash);
            created.setRole("ADMIN");
            created.setEnabled(true);
            created.setEmail("admin@sentinel.local");
            userMapper.insert(created);
            log.info("已根据环境变量 SENTINEL_ADMIN_PASSWORD 创建管理员用户 admin");
        } else {
            admin.setPassword(hash);
            userMapper.updateById(admin);
            log.info("已根据环境变量 SENTINEL_ADMIN_PASSWORD 更新管理员用户 admin 的密码");
        }
    }

    private List<User> loadEnabledUsers() {
        return userMapper.selectList(new QueryWrapper<User>().eq("enabled", true));
    }

    static List<String> jwtProblems(String secret) {
        if (!StringUtils.hasText(secret)) {
            return List.of("JWT 密钥为空。请设置环境变量 JWT_SECRET"
                    + "（至少 32 个字符，可用 openssl rand -base64 48 生成）。");
        }
        List<String> problems = new ArrayList<>();
        if (isKnownExample(secret)) {
            problems.add("JWT 密钥仍是 .env.example 或文档中的示例值。"
                    + "请执行 openssl rand -base64 48 生成随机字符串，写入环境变量 JWT_SECRET"
                    + "（至少 32 个字符），不要继续使用示例。");
        }
        String lowered = secret.toLowerCase(Locale.ROOT);
        for (String marker : FORBIDDEN_MARKERS) {
            if (lowered.contains(marker)) {
                problems.add("JWT 密钥包含占位标记「" + marker + "」。"
                        + "请换成随机值（openssl rand -base64 48）并写入环境变量 JWT_SECRET。");
            }
        }
        if (distinctCharacters(secret) < MIN_DISTINCT_CHARACTERS) {
            problems.add("JWT 密钥不同字符少于 8 个，熵过低。"
                    + "请换成随机值（openssl rand -base64 48）并写入环境变量 JWT_SECRET。");
        }
        return problems;
    }

    static List<String> defaultPasswordProblems(List<User> users, PasswordEncoder encoder) {
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        for (User user : users) {
            if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
                continue;
            }
            if (!StringUtils.hasText(user.getPassword())) {
                continue;
            }
            for (String candidate : DEFAULT_PASSWORDS) {
                if (encoder.matches(candidate, user.getPassword())) {
                    problems.add(defaultPasswordMessage(user.getUsername()));
                    break;
                }
            }
        }
        return problems;
    }

    static CheckMode resolveMode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return CheckMode.WARN;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "fail" -> CheckMode.FAIL;
            case "warn" -> CheckMode.WARN;
            case "off" -> CheckMode.OFF;
            default -> throw new IllegalStateException(
                    "配置 sentinel.security.credential-check 无效。只能是 fail、warn 或 off。"
                            + " YAML 中的 off 必须写成 \"off\"，否则会被解析成布尔值 false。");
        };
    }

    private static boolean isKnownExample(String secret) {
        for (String example : KNOWN_EXAMPLE_SECRETS) {
            if (example.equalsIgnoreCase(secret.trim())) {
                return true;
            }
        }
        return false;
    }

    private static int distinctCharacters(String secret) {
        return (int) secret.codePoints().distinct().count();
    }

    private static String defaultPasswordMessage(String username) {
        String name = StringUtils.hasText(username) ? username : "（未知用户名）";
        return "启用用户 " + name + " 仍在使用默认密码（admin123、user123、admin、password、123456 之一）。"
                + "请修改该用户密码。管理员可设置环境变量 SENTINEL_ADMIN_PASSWORD（至少 12 个字符的随机口令）后重启，"
                + "应用会创建或更新 admin 的 BCrypt 密码；其他用户请直接在数据库中修改。";
    }

    private static String failureReport(List<String> problems) {
        StringBuilder report = new StringBuilder("凭据安全检查未通过，应用拒绝启动。请逐项修复后重启：");
        for (String problem : problems) {
            report.append('\n').append("- ").append(problem);
        }
        return report.toString();
    }

    enum CheckMode {
        FAIL, WARN, OFF
    }
}
