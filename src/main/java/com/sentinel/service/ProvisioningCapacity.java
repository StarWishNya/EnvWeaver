package com.sentinel.service;

/**
 * 供给准入。单机用进程内预留防止并发把同一段空位算给多个任务。
 * 多实例共享数据库时，预留和锁都看不见对方，需要分布式锁（可用 {@link CacheService} 的 Redis）。
 */
public interface ProvisioningCapacity {

    /**
     * 现在启动该任务会不会超过环境数或容器数上限。
     * 计入占用环境、这些环境里的容器，以及本进程已经预留但环境行还没补齐的数量。
     */
    boolean wouldExceed(Long taskId);

    /**
     * 该任务要启动的容器数。依赖无法解析时返回 0，交给供给器把任务标为失败。
     */
    int containersNeeded(Long taskId);

    /**
     * 占住一个环境和对应容器数，直到环境行能反映真实占用或供给结束。
     */
    void reserve(Long taskId, int containers);

    /**
     * 释放进程内预留。环境行已经计入时，预留不再重复计数。
     */
    void release(Long taskId);

    /**
     * 监控用的库内占用和配置上限，不含进程内预留。
     */
    ProvisioningUsage usage();
}
