package com.sentinel.controller;

import com.sentinel.dto.ApiResponse;
import com.sentinel.enums.ErrorCode;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 把容器转发过来的 /error（例如没有映射的路径）收成统一的 JSON，而不是空的 403。
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<ApiResponse<Void>> error(HttpServletRequest request) {
        int status = resolveStatus(request);
        HttpStatus http = HttpStatus.resolve(status);
        if (http == null) {
            http = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        int code;
        String message;
        switch (http.value()) {
            case 404:
                code = ErrorCode.RESOURCE_NOT_FOUND.getCode();
                message = "资源不存在";
                break;
            case 405:
                code = ErrorCode.OPERATION_NOT_ALLOWED.getCode();
                message = "不支持的请求方法";
                break;
            case 401:
                code = ErrorCode.UNAUTHORIZED.getCode();
                message = ErrorCode.UNAUTHORIZED.getMessage();
                break;
            case 403:
                code = ErrorCode.ACCESS_DENIED.getCode();
                message = ErrorCode.ACCESS_DENIED.getMessage();
                break;
            default:
                code = ErrorCode.SYSTEM_ERROR.getCode();
                message = ErrorCode.SYSTEM_ERROR.getMessage();
                break;
        }
        return ResponseEntity.status(http).body(ApiResponse.error(code, message));
    }

    private static int resolveStatus(HttpServletRequest request) {
        Object raw = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (raw instanceof Integer) {
            return (Integer) raw;
        }
        if (raw != null) {
            try {
                return Integer.parseInt(raw.toString());
            } catch (NumberFormatException ignored) {
                return HttpStatus.INTERNAL_SERVER_ERROR.value();
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }
}
