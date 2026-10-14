package com.sentinel.controller;

import com.sentinel.dto.ApiResponse;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("统一错误页")
class ApiErrorControllerTest {

    private final ApiErrorController controller = new ApiErrorController();

    @Test
    @DisplayName("没有映射的路径按 404 返回统一 JSON")
    void unknownPathIsNotFound() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);

        ResponseEntity<ApiResponse<Void>> response = controller.error(request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(5005);
        assertThat(response.getBody().getMessage()).isEqualTo("资源不存在");
    }

    @Test
    @DisplayName("缺少状态码时按系统错误处理")
    void missingStatusIsServerError() {
        ResponseEntity<ApiResponse<Void>> response = controller.error(new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(5000);
    }
}
