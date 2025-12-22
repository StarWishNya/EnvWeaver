package com.sentinel.enums;

import lombok.Getter;

/**
 * 错误码枚举
 * 
 * @author Sentinel Team
 */
@Getter
public enum ErrorCode {

    // 1xxx - 认证相关错误
    UNAUTHORIZED(1001, "未授权，请先登录"),
    TOKEN_EXPIRED(1002, "Token 已过期"),
    TOKEN_INVALID(1003, "Token 无效"),
    TOKEN_BLACKLISTED(1004, "Token 已被加入黑名单"),
    ACCOUNT_LOCKED(1005, "账户已被锁定"),
    ACCOUNT_DISABLED(1006, "账户已被禁用"),
    BAD_CREDENTIALS(1007, "用户名或密码错误"),
    ACCESS_DENIED(1008, "权限不足"),

    // 2xxx - 任务相关错误
    TASK_NOT_FOUND(2001, "任务不存在"),
    TASK_CREATE_FAILED(2002, "任务创建失败"),
    TASK_CANCEL_FAILED(2003, "任务取消失败"),
    TASK_STATUS_INVALID(2004, "任务状态无效"),
    TASK_ALREADY_RUNNING(2005, "任务已在运行中"),

    // 3xxx - 环境相关错误
    ENVIRONMENT_NOT_FOUND(3001, "环境不存在"),
    ENVIRONMENT_CREATE_FAILED(3002, "环境创建失败"),
    ENVIRONMENT_RELEASE_FAILED(3003, "环境释放失败"),
    ENVIRONMENT_NOT_AVAILABLE(3004, "环境不可用"),
    ENVIRONMENT_BUSY(3005, "环境正忙"),

    // 4xxx - 诊断相关错误
    DIAGNOSIS_FAILED(4001, "诊断失败"),
    DIAGNOSIS_NOT_FOUND(4002, "诊断结果不存在"),
    AI_SERVICE_UNAVAILABLE(4003, "AI 服务不可用"),
    AI_SERVICE_TIMEOUT(4004, "AI 服务超时"),

    // 5xxx - 系统相关错误
    SYSTEM_ERROR(5000, "系统错误"),
    DATABASE_ERROR(5001, "数据库错误"),
    REDIS_ERROR(5002, "Redis 错误"),
    DOCKER_ERROR(5003, "Docker 错误"),
    VALIDATION_ERROR(5004, "参数校验失败"),
    RESOURCE_NOT_FOUND(5005, "资源不存在"),
    RESOURCE_ALREADY_EXISTS(5006, "资源已存在"),
    OPERATION_NOT_ALLOWED(5007, "操作不允许");

    private final Integer code;
    private final String message;

    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
