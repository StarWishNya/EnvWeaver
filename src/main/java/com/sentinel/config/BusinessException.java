package com.sentinel.config;

import com.sentinel.enums.ErrorCode;
import lombok.Getter;

/**
 * 业务异常类
 * 
 * @author Sentinel Team
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    /**
     * 可选的结构化详情，例如未知依赖的候选版本。不含凭据。
     */
    private final Object data;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public BusinessException(Integer code, String message) {
        this(code, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode.getCode(), message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Object data) {
        this(errorCode.getCode(), message, data);
    }

    public BusinessException(Integer code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }
}
