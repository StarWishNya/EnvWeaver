package com.sentinel.controller;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.sentinel.config.GlobalExceptionHandler;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 系统指标与组件健康检查接口。
 */
@WebMvcTest(MetricsController.class)
@AutoConfigureMockMvc
@Import({
        MetricsService.class,
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        GlobalExceptionHandler.class
})
@EnableConfigurationProperties(SecurityProperties.class)
@DisplayName("MetricsController 测试")
class MetricsControllerTest {

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
    void setUp() {
        when(dbHealthChecker.name()).thenReturn(ComponentHealthChecker.DB);
        when(redisHealthChecker.name()).thenReturn(ComponentHealthChecker.REDIS);
        when(dockerHealthChecker.name()).thenReturn(ComponentHealthChecker.DOCKER);
        when(dbHealthChecker.up()).thenReturn(true);
        when(redisHealthChecker.up()).thenReturn(true);
        when(dockerHealthChecker.up()).thenReturn(true);
        stubContainers(2, 1, 3);
    }

    @Test
    @WithMockUser
    @DisplayName("已登录时返回 SystemMetrics 的全部字段")
    void returnsSystemMetricsShape() throws Exception {
        mockMvc.perform(get("/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.fromCache").value(false))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.data.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.data.cpu.usage").exists())
                .andExpect(jsonPath("$.data.cpu.cores").exists())
                .andExpect(jsonPath("$.data.memory.used").exists())
                .andExpect(jsonPath("$.data.memory.total").exists())
                .andExpect(jsonPath("$.data.memory.usage").exists())
                .andExpect(jsonPath("$.data.disk.used").exists())
                .andExpect(jsonPath("$.data.disk.total").exists())
                .andExpect(jsonPath("$.data.disk.usage").exists())
                .andExpect(jsonPath("$.data.containers.running").value(2))
                .andExpect(jsonPath("$.data.containers.paused").value(1))
                .andExpect(jsonPath("$.data.containers.stopped").value(3))
                .andExpect(jsonPath("$.data.activeEnvironments").value(0))
                .andExpect(jsonPath("$.data.activeContainers").value(0))
                .andExpect(jsonPath("$.data.queuedTasks").value(0))
                .andExpect(jsonPath("$.data.limits.maxActiveEnvironments").value(10))
                .andExpect(jsonPath("$.data.limits.maxContainers").value(30));
    }

    @Test
    @DisplayName("未登录访问系统指标返回 401")
    void anonymousMetricsIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("未授权，请先登录"));
    }

    @Test
    @DisplayName("匿名健康检查在全部 UP 时返回 200 且不包 ApiResponse")
    void anonymousHealthIsUp() throws Exception {
        mockMvc.perform(get("/api/metrics/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db").value("UP"))
                .andExpect(jsonPath("$.components.redis").value("UP"))
                .andExpect(jsonPath("$.components.docker").value("UP"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("有组件 DOWN 时匿名健康检查返回 503，且不泄露异常")
    void healthIsServiceUnavailableWhenComponentIsDown() throws Exception {
        when(dbHealthChecker.up()).thenThrow(new IllegalStateException("jdbc:mysql://db.internal:3306/sentinel"));
        when(redisHealthChecker.up()).thenReturn(true);
        when(dockerHealthChecker.up()).thenReturn(false);

        mockMvc.perform(get("/api/metrics/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.db").value("DOWN"))
                .andExpect(jsonPath("$.components.redis").value("UP"))
                .andExpect(jsonPath("$.components.docker").value("DOWN"))
                .andExpect(content().string(not(containsString("db.internal"))))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    private void stubContainers(int running, int paused, int stopped) {
        List<Container> listed = containers(running, paused, stopped);
        ListContainersCmd command = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(command);
        when(command.withShowAll(true)).thenReturn(command);
        when(command.exec()).thenReturn(listed);
    }

    private static List<Container> containers(int running, int paused, int stopped) {
        ArrayList<Container> containers = new ArrayList<>();
        for (int i = 0; i < running; i++) {
            containers.add(container("running"));
        }
        for (int i = 0; i < paused; i++) {
            containers.add(container("paused"));
        }
        for (int i = 0; i < stopped; i++) {
            containers.add(container("exited"));
        }
        return containers;
    }

    private static Container container(String state) {
        Container container = new Container();
        try {
            Field field = Container.class.getDeclaredField("state");
            field.setAccessible(true);
            field.set(container, state);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return container;
    }
}
