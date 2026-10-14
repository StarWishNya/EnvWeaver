package com.sentinel.service;

/**
 * 单项依赖健康检查。实现不得把异常信息返回给调用方。
 */
public interface ComponentHealthChecker {

    String DB = "db";

    String REDIS = "redis";

    String DOCKER = "docker";

    /**
     * 组件名：db、redis 或 docker。
     */
    String name();

    /**
     * 依赖是否可用。失败时返回 false，不要抛出异常。
     */
    boolean up();
}
