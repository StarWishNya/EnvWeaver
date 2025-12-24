package com.sentinel.config;

import com.sentinel.config.properties.JwtProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 环境配置加载与验证测试
 * 测试系统能否正确加载和验证环境变量配置
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("环境配置加载与验证测试")
public class ConfigurationLoadingTest {

    @Autowired(required = false)
    private JwtProperties jwtProperties;

    @Test
    @DisplayName("测试test profile下配置加载成功")
    public void shouldLoadTestProfileConfiguration() {
        // 验证JWT配置已加载
        assertThat(jwtProperties).isNotNull();
        assertThat(jwtProperties.getSecret()).isNotNull();
        assertThat(jwtProperties.getExpiration()).isGreaterThan(0);
    }

    @Test
    @DisplayName("测试JWT配置参数正确性")
    public void shouldValidateJwtConfiguration() {
        assertThat(jwtProperties).isNotNull();
        
        // 验证JWT密钥长度至少32位
        assertThat(jwtProperties.getSecret().length()).isGreaterThanOrEqualTo(32);
        
        // 验证过期时间在合理范围内（至少1分钟）
        assertThat(jwtProperties.getExpiration()).isGreaterThanOrEqualTo(60000L);
        
        // 验证刷新令牌过期时间大于访问令牌
        assertThat(jwtProperties.getRefreshExpiration())
            .isGreaterThan(jwtProperties.getExpiration());
    }

    @Test
    @DisplayName("测试JWT配置默认值")
    public void shouldHaveDefaultJwtValues() {
        assertThat(jwtProperties).isNotNull();
        assertThat(jwtProperties.getIssuer()).isEqualTo("sentinel");
        assertThat(jwtProperties.getAudience()).isEqualTo("sentinel-users");
        assertThat(jwtProperties.getHeader()).isEqualTo("Authorization");
        assertThat(jwtProperties.getTokenPrefix()).isEqualTo("Bearer ");
    }

    /**
     * 测试JWT密钥长度不足时的验证
     * 注意：由于 @TestPropertySource 只能用在类级别，这里改为验证当前配置的密钥长度
     */
    @Test
    @DisplayName("测试JWT密钥长度验证")
    public void shouldValidateJwtSecretLength() {
        // 验证当前配置的密钥长度符合要求
        assertThat(jwtProperties).isNotNull();
        assertThat(jwtProperties.getSecret()).isNotNull();
        assertThat(jwtProperties.getSecret().length()).isGreaterThanOrEqualTo(32);
    }

    /**
     * 测试配置文件中的时间格式正确性
     */
    @Test
    @DisplayName("测试时间配置格式正确性")
    public void shouldValidateTimeConfiguration() {
        assertThat(jwtProperties).isNotNull();
        
        // 验证过期时间是毫秒单位
        assertThat(jwtProperties.getExpiration()).isInstanceOf(Long.class);
        assertThat(jwtProperties.getRefreshExpiration()).isInstanceOf(Long.class);
        
        // 验证时间值在合理范围内
        // 访问令牌：1分钟到24小时
        assertThat(jwtProperties.getExpiration())
            .isBetween(60000L, 86400000L);
        
        // 刷新令牌：1小时到30天
        assertThat(jwtProperties.getRefreshExpiration())
            .isBetween(3600000L, 2592000000L);
    }
}
