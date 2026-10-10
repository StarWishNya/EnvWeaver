package com.sentinel.controller;

import com.sentinel.config.CatalogConfiguration;
import com.sentinel.config.GlobalExceptionHandler;
import com.sentinel.config.JwtAuthenticationFilter;
import com.sentinel.config.SecurityConfig;
import com.sentinel.config.properties.SecurityProperties;
import com.sentinel.mapper.UserMapper;
import com.sentinel.service.CacheService;
import com.sentinel.service.UserDetailsServiceImpl;
import com.sentinel.util.JwtUtil;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 目录接口。已登录的任意角色可以读，匿名返回 401。
 */
@WebMvcTest(CatalogController.class)
@AutoConfigureMockMvc(addFilters = true)
@Import({
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        CatalogConfiguration.class
})
@EnableConfigurationProperties(SecurityProperties.class)
@DisplayName("CatalogController 测试")
class CatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private CacheService cacheService;

    @MockBean
    private UserMapper userMapper;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("返回中间件种类、版本、别名、默认版本、镜像和端口")
    void middlewareCatalog() throws Exception {
        mockMvc.perform(get("/api/catalog/middleware"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.revision").value("2026-10-10.2"))
                .andExpect(jsonPath("$.data.kinds[0].kind").value("mysql"))
                .andExpect(jsonPath("$.data.kinds[0].defaultVersion").value("8.0"))
                .andExpect(jsonPath("$.data.kinds[0].versions[0].version").value("5.7"))
                .andExpect(jsonPath("$.data.kinds[0].versions[0].image").value("mysql:5.7"))
                .andExpect(jsonPath("$.data.kinds[0].versions[0].containerPort").value(3306))
                .andExpect(jsonPath("$.data.kinds[0].versions[1].aliases[0]").value("8"))
                .andExpect(jsonPath("$.data.kinds[0].versions[1].image").value("mysql:8.0"))
                .andExpect(jsonPath("$.data.kinds[1].kind").value("redis"))
                .andExpect(jsonPath("$.data.kinds[1].defaultVersion").value("7"))
                .andExpect(jsonPath("$.data.kinds[1].versions[1].aliases[0]").value("7.0"))
                .andExpect(jsonPath("$.data.kinds[1].versions[1].aliases[1]").value("7.2"))
                .andExpect(jsonPath("$.data.kinds[4].kind").value("rabbitmq"))
                .andExpect(jsonPath("$.data.kinds[4].defaultVersion").value("3.13"))
                .andExpect(jsonPath("$.data.kinds[4].versions[0].image").value("rabbitmq:3.13-management-alpine"))
                .andExpect(jsonPath("$.data.kinds[4].versions[0].containerPort").value(5672))
                .andExpect(jsonPath("$.data.kinds[6].kind").value("kafka"))
                .andExpect(jsonPath("$.data.kinds[6].versions[0].image").value("apache/kafka:3.7.0"))
                .andExpect(jsonPath("$.data.kinds[0].versions[1].env").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("返回环境模板")
    void templateCatalog() throws Exception {
        mockMvc.perform(get("/api/catalog/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.templates[0].name").value("web-stack"))
                .andExpect(jsonPath("$.data.templates[0].displayName").value("Web 技术栈"))
                .andExpect(jsonPath("$.data.templates[0].dependencies[2]").value("nginx"))
                .andExpect(jsonPath("$.data.templates[1].name").value("data-stack"))
                .andExpect(jsonPath("$.data.templates[1].displayName").value("数据技术栈"))
                .andExpect(jsonPath("$.data.templates[1].dependencies[0]").value("postgres:16"))
                .andExpect(jsonPath("$.data.templates[1].dependencies[1]").value("redis:7"))
                .andExpect(jsonPath("$.data.templates[2].name").value("mq-stack"));
    }

    @Test
    @DisplayName("未登录不能读目录")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/catalog/middleware"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
        mockMvc.perform(get("/api/catalog/templates"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }
}
