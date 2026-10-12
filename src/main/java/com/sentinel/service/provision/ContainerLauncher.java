package com.sentinel.service.provision;

import java.util.Map;

/**
 * 用 Docker 启动、检查和删除供给出来的容器。
 */
public interface ContainerLauncher {

    /**
     * 本地已有镜像则直接返回。缺失时按配置拉取，否则失败。
     */
    void ensureImage(String image);

    /**
     * 创建并启动容器。启动失败时删掉已创建的容器。
     */
    StartedContainer start(Launch launch);

    /**
     * 强制删除容器。容器已经不存在视为成功。
     */
    void remove(String containerId);

    /**
     * 查看容器是否在运行，以及 Docker 健康检查状态。没有健康检查时 healthStatus 为 null。
     */
    Snapshot inspect(String containerId);

    /**
     * 容器记录是否还在 Docker 里。
     * 没有容器 ID，或 Docker 返回 NotFound 时为 false。
     * 其它 Docker 错误原样抛出，调用方本轮不要据此改环境。
     */
    boolean exists(String containerId);

    /**
     * 容器最近的标准输出和标准错误，最多 maxLines 行。读不到时返回空字符串，不抛异常。
     */
    String recentLogs(String containerId, int maxLines);

    /**
     * 一次启动请求。env 可能含密码，调用方不要打印它。
     */
    record Launch(String image,
                  String name,
                  int containerPort,
                  int hostPort,
                  Map<String, String> env,
                  Map<String, String> labels) {
    }

    /**
     * 已启动的容器。healthcheckDefined 表示镜像定义了 Docker HEALTHCHECK。
     */
    record StartedContainer(String containerId, boolean healthcheckDefined) {
    }

    /**
     * 容器当前状态。healthStatus 为 healthy / unhealthy / starting，或没有健康检查时为 null。
     */
    record Snapshot(boolean running, String healthStatus) {
    }
}
