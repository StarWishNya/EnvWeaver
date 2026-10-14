package com.sentinel.service;

import com.sentinel.domain.Environment;
import com.sentinel.domain.Task;

/**
 * 按任务依赖创建测试环境。
 */
public interface EnvironmentProvisioner {

    /**
     * 为任务启动依赖容器并返回 READY 的环境。任务已有未释放的环境时直接返回该环境。
     * 失败时清理已启动的容器，环境标为 ERROR，并抛出 BusinessException。
     */
    Environment provision(Task task);
}
