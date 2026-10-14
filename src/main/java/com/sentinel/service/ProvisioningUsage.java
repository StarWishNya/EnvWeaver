package com.sentinel.service;

/**
 * 当前占用与配置上限。上限小于等于 0 表示不限制。
 * activeEnvironments / activeContainers 只统计库里的占用环境，不含尚未落库的进程内预留。
 */
public record ProvisioningUsage(
        int activeEnvironments,
        int activeContainers,
        int queuedTasks,
        int maxActiveEnvironments,
        int maxContainers) {

    public static ProvisioningUsage defaults() {
        return new ProvisioningUsage(0, 0, 0, 10, 30);
    }
}
