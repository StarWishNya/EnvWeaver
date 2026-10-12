package com.sentinel.event;

/**
 * 环境已变成 IDLE。用来在容量释放后尝试出队。
 */
public record EnvironmentReleasedEvent(Long environmentId) {
}
