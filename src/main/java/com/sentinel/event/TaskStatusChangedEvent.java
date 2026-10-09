package com.sentinel.event;

/**
 * 任务状态已变化。状态未变时不发布。
 */
public record TaskStatusChangedEvent(Long taskId, String oldStatus, String newStatus) {
}
