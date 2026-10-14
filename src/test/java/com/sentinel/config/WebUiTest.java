package com.sentinel.config;

import com.sentinel.config.properties.SecurityProperties;
import com.sentinel.service.UserDetailsServiceImpl;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内置控制台的安全链：匿名可以读取静态页面，接口仍然要登录。
 */
@WebMvcTest(controllers = WebUiTest.PageAnchor.class)
@AutoConfigureMockMvc(addFilters = true)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WebUiTest.SecurityPropertiesConfiguration.class})
@DisplayName("内置控制台")
class WebUiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void passThroughJwtFilter() throws Exception {
        SecurityContextHolder.clearContext();
        lenient().doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(
                any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
    }

    @Test
    @DisplayName("匿名可以打开控制台首页")
    void anonymousIndexIsHtml() throws Exception {
        // 欢迎页在容器里是一次内部 forward，客户端拿到的仍是 index.html。
        htmlPage("/")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("id=\"app\"")))
                .andExpect(content().string(containsString("/assets/app.js")));
    }

    @Test
    @DisplayName("匿名可以打开 index.html")
    void anonymousIndexFileIsHtml() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("id=\"app\"")));
    }

    @Test
    @DisplayName("匿名可以读取脚本和样式")
    void anonymousAssetsArePublic() throws Exception {
        MvcResult script = mockMvc.perform(get("/assets/app.js"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("javascript")))
                .andExpect(content().string(containsString("sessionStorage")))
                .andReturn();
        // 静态资源没有 charset，MockMvc 按 ISO-8859-1 解开会把中文看花。按 UTF-8 看源码。
        String source = new String(script.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(source)
                .contains("visibilityState")
                .contains("sentinel.ui.")
                .contains("自动刷新")
                .contains("上一页")
                .contains("下一页")
                .contains("页，共 ")
                .contains("实时")
                .contains("暂停")
                .contains("有新日志")
                .contains("结束后自动刷新诊断")
                .contains("LOG_LINE_CAP")
                .contains("60000")
                .doesNotContain("innerHTML")
                .doesNotContain("localStorage.setItem(ACCESS_KEY)")
                .doesNotContain("localStorage.setItem(REFRESH_KEY)");

        mockMvc.perform(get("/assets/app.css"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("css")));
    }

    @Test
    @DisplayName("静态路径只放行 GET")
    void staticPathsRejectOtherMethods() throws Exception {
        mockMvc.perform(post("/"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));

        mockMvc.perform(post("/index.html"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未知页面和任务接口仍然拒绝匿名访问")
    void unknownAndApiStayProtected() throws Exception {
        mockMvc.perform(get("/not-a-console-page"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("未授权，请先登录"));

        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)));
    }

    /**
     * MockMvc 停在欢迎页的 forward。顺着转发再取一次，得到浏览器实际看到的 HTML。
     */
    private ResultActions htmlPage(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        String forwarded = result.getResponse().getForwardedUrl();
        if (forwarded == null) {
            return mockMvc.perform(get(path));
        }
        String target = forwarded.startsWith("/") ? forwarded : "/" + forwarded;
        return mockMvc.perform(get(target));
    }

    /**
     * 占位控制器，避免切片扫进全部业务接口。静态资源仍由 MVC 默认映射提供。
     */
    @RestController
    public static class PageAnchor {
    }

    @Configuration
    @EnableConfigurationProperties(SecurityProperties.class)
    static class SecurityPropertiesConfiguration {
    }
}
