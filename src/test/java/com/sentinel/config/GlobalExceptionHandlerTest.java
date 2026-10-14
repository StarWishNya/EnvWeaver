package com.sentinel.config;

import com.sentinel.dto.ApiResponse;
import com.sentinel.enums.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务异常到 HTTP 状态的映射。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("任务状态并发冲突映射为 HTTP 409")
    void statusConflictIsHttp409() {
        BusinessException ex = new BusinessException(ErrorCode.TASK_STATUS_CONFLICT);
        ResponseEntity<ApiResponse<Object>> response = handler.handleBusinessException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(GlobalExceptionHandler.resolveHttpStatus(ex.getCode())).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.TASK_STATUS_CONFLICT.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo("任务状态已被并发修改，请重试");
    }

    @Test
    @DisplayName("已有错误码的 HTTP 映射保持不变")
    void existingCodesKeepTheirHttpStatus() {
        assertThat(GlobalExceptionHandler.resolveHttpStatus(ErrorCode.UNAUTHORIZED.getCode()))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(GlobalExceptionHandler.resolveHttpStatus(ErrorCode.ACCESS_DENIED.getCode()))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(GlobalExceptionHandler.resolveHttpStatus(ErrorCode.TASK_NOT_FOUND.getCode()))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(GlobalExceptionHandler.resolveHttpStatus(ErrorCode.DOCKER_ERROR.getCode()))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(GlobalExceptionHandler.resolveHttpStatus(ErrorCode.OPERATION_NOT_ALLOWED.getCode()))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(GlobalExceptionHandler.resolveHttpStatus(ErrorCode.TASK_STATUS_INVALID.getCode()))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
