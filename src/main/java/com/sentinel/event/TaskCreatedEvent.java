package com.sentinel.event;

/**
 * 任务已创建。由 TaskService 在事务提交后发布。
 */
public record TaskCreatedEvent(Long taskId) {
}
