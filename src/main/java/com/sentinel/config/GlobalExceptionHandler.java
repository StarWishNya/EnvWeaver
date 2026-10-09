package com.sentinel.config;

import com.sentinel.dto.ApiResponse;
import com.sentinel.enums.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 * 
 * @author Sentinel Team
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, String>> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        log.warn("参数校验失败: {}", errors);
        ApiResponse<Map<String, String>> response = ApiResponse.error(
                ErrorCode.VALIDATION_ERROR.getCode(), "参数校验失败");
        response.setData(errors);
        return response;
    }

    /**
     * 请求体不是合法 JSON，或字段类型无法反序列化。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体无法解析: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return ApiResponse.error(ErrorCode.VALIDATION_ERROR.getCode(), "请求体格式错误");
    }

    /**
     * 缺少必填请求头或查询参数，或路径/查询参数类型不匹配。
     */
    @ExceptionHandler({
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBadRequestParameters(Exception ex) {
        log.warn("请求参数错误: {}", ex.getMessage());
        return ApiResponse.error(ErrorCode.VALIDATION_ERROR.getCode(), "请求参数错误");
    }

    /**
     * Content-Type 不被该接口接受。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ApiResponse<Void> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("不支持的 Content-Type: {}", ex.getContentType());
        return ApiResponse.error(ErrorCode.VALIDATION_ERROR.getCode(), "不支持的 Content-Type");
    }

    /**
     * 路径存在但 HTTP 方法不匹配。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("不支持的请求方法: {}", ex.getMethod());
        return ApiResponse.error(ErrorCode.OPERATION_NOT_ALLOWED.getCode(), "不支持的请求方法");
    }

    /**
     * 唯一约束冲突（例如重复的任务编码）。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleDuplicateKey(DuplicateKeyException ex) {
        log.warn("唯一约束冲突");
        return ApiResponse.error(ErrorCode.RESOURCE_ALREADY_EXISTS.getCode(), "资源已存在");
    }

    /**
     * 字段超长、非法 JSON 列等数据约束失败。不把 SQL 返回给客户端。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("数据约束失败: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return ApiResponse.error(ErrorCode.VALIDATION_ERROR.getCode(), "请求数据不符合约束");
    }

    /**
     * 处理认证异常
     */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleAuthenticationException(AuthenticationException ex) {
        log.warn("认证失败: {}", ex.getMessage());
        
        if (ex instanceof BadCredentialsException) {
            return ApiResponse.error(ErrorCode.BAD_CREDENTIALS.getCode(), ErrorCode.BAD_CREDENTIALS.getMessage());
        }
        
        return ApiResponse.error(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
    }

    /**
     * 处理权限不足异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleAccessDeniedException(AccessDeniedException ex) {
        log.warn("权限不足: {}", ex.getMessage());
        return ApiResponse.error(ErrorCode.ACCESS_DENIED.getCode(), ErrorCode.ACCESS_DENIED.getMessage());
    }

    /**
     * 处理业务异常。HTTP 状态按错误码映射：认证失败 401，权限不足 403，资源不存在 404，Docker 错误 503，其余 400。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.warn("业务异常: code={}, message={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(resolveHttpStatus(ex.getCode()))
                .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    static HttpStatus resolveHttpStatus(Integer code) {
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        if (code.equals(ErrorCode.ACCESS_DENIED.getCode())) {
            return HttpStatus.FORBIDDEN;
        }
        if (code >= 1001 && code <= 1007) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (code.equals(ErrorCode.TASK_NOT_FOUND.getCode())
                || code.equals(ErrorCode.ENVIRONMENT_NOT_FOUND.getCode())
                || code.equals(ErrorCode.DIAGNOSIS_NOT_FOUND.getCode())
                || code.equals(ErrorCode.RESOURCE_NOT_FOUND.getCode())) {
            return HttpStatus.NOT_FOUND;
        }
        if (code.equals(ErrorCode.DOCKER_ERROR.getCode())) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.BAD_REQUEST;
    }

    /**
     * 处理系统异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception ex) {
        log.error("系统异常", ex);
        return ApiResponse.error(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage());
    }
}
