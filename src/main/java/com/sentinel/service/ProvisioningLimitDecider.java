package com.sentinel.service;

/**
 * 纯判断：再启动一个任务会不会超过环境数或容器数上限。
 * 上限小于等于 0 表示该项不限制。
 */
public final class ProvisioningLimitDecider {

    private ProvisioningLimitDecider() {
    }

    /**
     * @param activeEnvironments 已占用环境数（不含本次任务）
     * @param activeContainers   已占用容器数（不含本次任务）
     * @param containersNeeded   本次任务要启动的容器数
     * @param maxActiveEnvironments 环境上限，小于等于 0 为不限制
     * @param maxContainers     容器上限，小于等于 0 为不限制
     */
    public static boolean exceeds(int activeEnvironments,
                                  int activeContainers,
                                  int containersNeeded,
                                  int maxActiveEnvironments,
                                  int maxContainers) {
        if (maxActiveEnvironments > 0 && activeEnvironments + 1 > maxActiveEnvironments) {
            return true;
        }
        int needed = Math.max(containersNeeded, 0);
        return maxContainers > 0 && activeContainers + needed > maxContainers;
    }
}
