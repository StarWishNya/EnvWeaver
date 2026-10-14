package com.sentinel.controller;

import com.github.dockerjava.api.DockerClient;
import com.sentinel.config.JwtAuthenticationFilter;
import com.sentinel.config.SecurityConfig;
import com.sentinel.config.properties.SecurityProperties;
import com.sentinel.mapper.UserMapper;
import com.sentinel.service.CacheService;
import com.sentinel.service.ComponentHealthChecker;
import com.sentinel.service.DbHealthChecker;
import com.sentinel.service.DockerHealthChecker;
import com.sentinel.service.MetricsService;
import com.sentinel.service.RedisHealthChecker;
import com.sentinel.service.UserDetailsServiceImpl;
import com.sentinel.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HealthController 测试类。
 * /api/health 匿名可访问，并用 MetricsService 的检查器汇总 db、redis、docker。
 */
@WebMvcTest(HealthController.class)
@AutoConfigureMockMvc
@Import({
        MetricsService.class,
        SecurityConfig.class,
        JwtAuthenticationFilter.class
})
@EnableConfigurationProperties(SecurityProperties.class)
@DisplayName("HealthController 测试")
public class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DockerClient dockerClient;

    @MockBean
    private DbHealthChecker dbHealthChecker;

    @MockBean
    private RedisHealthChecker redisHealthChecker;

    @MockBean
    private DockerHealthChecker dockerHealthChecker;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private CacheService cacheService;

    @MockBean
    private UserMapper userMapper;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    public void setUp() {
        when(dbHealthChecker.name()).thenReturn(ComponentHealthChecker.DB);
        when(redisHealthChecker.name()).thenReturn(ComponentHealthChecker.REDIS);
        when(dockerHealthChecker.name()).thenReturn(ComponentHealthChecker.DOCKER);
        when(dbHealthChecker.up()).thenReturn(true);
        when(redisHealthChecker.up()).thenReturn(true);
        when(dockerHealthChecker.up()).thenReturn(true);
    }

    @Test
    @DisplayName("依赖都 UP 时返回 200 和组件状态")
    public void shouldReturnHealthStatus_whenHealthEndpointCalled() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.application").value("Sentinel"))
                .andExpect(jsonPath("$.version").value("1.0.0-SNAPSHOT"))
                .andExpect(jsonPath("$.components.db").value("UP"))
                .andExpect(jsonPath("$.components.redis").value("UP"))
                .andExpect(jsonPath("$.components.docker").value("UP"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @DisplayName("健康检查接口无需认证")
    public void shouldAccessHealthEndpoint_withoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("健康检查返回原有字段和组件")
    public void shouldReturnAllRequiredFields() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.application").exists())
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.components.db").exists())
                .andExpect(jsonPath("$.components.redis").exists())
                .andExpect(jsonPath("$.components.docker").exists());
    }

    @Test
    @DisplayName("仅 Docker 为 DOWN 时仍返回 200")
    public void dockerDownAloneStaysOk() throws Exception {
        when(dockerHealthChecker.up()).thenReturn(false);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db").value("UP"))
                .andExpect(jsonPath("$.components.redis").value("UP"))
                .andExpect(jsonPath("$.components.docker").value("DOWN"));
    }

    @Test
    @DisplayName("数据库 DOWN 时返回 503，且不泄露异常")
    public void databaseDownIsServiceUnavailable() throws Exception {
        when(dbHealthChecker.up()).thenThrow(new IllegalStateException("jdbc:mysql://db.internal:3306/sentinel"));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.db").value("DOWN"))
                .andExpect(jsonPath("$.components.redis").value("UP"))
                .andExpect(jsonPath("$.components.docker").value("UP"))
                .andExpect(jsonPath("$.application").value("Sentinel"))
                .andExpect(content().string(not(containsString("db.internal"))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    @DisplayName("Redis DOWN 时返回 503")
    public void redisDownIsServiceUnavailable() throws Exception {
        when(redisHealthChecker.up()).thenReturn(false);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.db").value("UP"))
                .andExpect(jsonPath("$.components.redis").value("DOWN"))
                .andExpect(jsonPath("$.components.docker").value("UP"));
    }
}
