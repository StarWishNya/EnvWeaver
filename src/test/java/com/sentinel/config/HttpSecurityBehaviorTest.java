package com.sentinel.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实端口上的安全行为：未登录 401 JSON、跨域预检、匿名健康检查、未映射路径 404。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability(tracing = false)
@ActiveProfiles("test")
@DisplayName("HTTP 安全行为")
class HttpSecurityBehaviorTest {

    @LocalServerPort
    private int port;

    private HttpClient client;

    @BeforeEach
    void setUp() {
        client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    @DisplayName("未登录访问任务接口返回 401 JSON")
    void anonymousTaskRequestIsUnauthorizedJson() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/tasks")).GET().build());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":1001").contains("未授权，请先登录");
    }

    @Test
    @DisplayName("匿名健康检查只有状态，没有磁盘和数据库详情")
    void healthHidesDetailsFromAnonymous() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/actuator/health")).GET().build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        assertThat(response.body()).doesNotContain("diskSpace").doesNotContain("components");
    }

    @Test
    @DisplayName("测试 profile 配置的前端源可以通过预检")
    void corsPreflightAllowsConfiguredOrigin() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/tasks"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://test-frontend:3000")
                .header("Access-Control-Request-Method", "GET")
                .build();

        HttpResponse<String> response = send(request);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                .contains("http://test-frontend:3000");
    }

    @Test
    @DisplayName("已登录访问不存在的路径返回 404 JSON")
    void unknownPathIsNotFoundJson() throws Exception {
        HttpRequest login = HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .build();
        HttpResponse<String> loginResponse = send(login);
        assertThat(loginResponse.statusCode()).isEqualTo(200);
        String token = tokenOf(loginResponse.body());

        HttpRequest request = HttpRequest.newBuilder(uri("/api/no-such-route"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = send(request);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":5005").contains("资源不存在");
    }

    @Test
    @DisplayName("Prometheus 指标需要管理员身份")
    void prometheusRequiresAdmin() throws Exception {
        HttpResponse<String> anonymous = send(HttpRequest.newBuilder(uri("/actuator/prometheus")).GET().build());
        assertThat(anonymous.statusCode()).isEqualTo(401);

        HttpResponse<String> asUser = send(HttpRequest.newBuilder(uri("/actuator/prometheus"))
                .header("Authorization", "Bearer " + loginAs("user", "user123"))
                .GET()
                .build());
        assertThat(asUser.statusCode()).isEqualTo(403);

        HttpResponse<String> asAdmin = send(HttpRequest.newBuilder(uri("/actuator/prometheus"))
                .header("Authorization", "Bearer " + loginAs("admin", "admin123"))
                .GET()
                .build());
        assertThat(asAdmin.statusCode()).as(asAdmin.body()).isEqualTo(200);
        assertThat(asAdmin.body()).contains("jvm_");
    }

    @Test
    @DisplayName("密码错误返回 401")
    void wrongPasswordIsUnauthorized() throws Exception {
        String username = "no-such-user-" + System.nanoTime();
        HttpResponse<String> response = send(loginRequest(username, "definitely-wrong"));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":1007");
    }

    private HttpRequest loginRequest(String username, String password) {
        return HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .build();
    }

    private String loginAs(String username, String password) throws Exception {
        HttpResponse<String> response = send(loginRequest(username, password));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return tokenOf(response.body());
    }

    @Test
    @DisplayName("Swagger 入口不再被安全配置拒绝")
    void swaggerUiEntryIsNotForbidden() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/swagger-ui.html")).GET().build());

        assertThat(response.statusCode()).isNotEqualTo(403);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String tokenOf(String json) {
        String key = "\"accessToken\":\"";
        int start = json.indexOf(key);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int from = start + key.length();
        int end = json.indexOf('"', from);
        return json.substring(from, end);
    }
}
