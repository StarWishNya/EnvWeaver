package com.sentinel.config.properties;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 环境供给配置。依赖目录的内置默认值在 DependencyCatalog 中，这里的条目按名称覆盖。
 * 容器环境变量可能包含数据库密码，禁止把本对象打进日志。
 */
@Data
@ConfigurationProperties(prefix = "sentinel.provisioning")
public class ProvisioningProperties {

    /**
     * 环境过期时间，默认 2 小时。
     */
    private Duration ttl = Duration.ofHours(2);

    /**
     * 主机端口闭区间，格式 start-end，默认 30000-39999。
     */
    private String portRange = "30000-39999";

    /**
     * 本地没有镜像时是否拉取，默认开启。
     */
    private boolean pullMissing = true;

    /**
     * 拉取镜像的最长时间，默认 5 分钟。
     */
    private Duration pullTimeout = Duration.ofMinutes(5);

    /**
     * 等待容器运行且端口可连接的最长时间，默认 90 秒。
     */
    private Duration readyTimeout = Duration.ofSeconds(90);

    /**
     * 写入 access_url，并作为就绪探测的连接主机，默认 localhost。
     */
    private String host = "localhost";

    /**
     * 容器端口发布到宿主机的监听地址，默认 127.0.0.1。环境内的数据库使用固定弱口令，改成 0.0.0.0 会暴露到外网。
     */
    private String bindAddress = "127.0.0.1";

    /**
     * 按依赖名覆盖镜像、端口和环境变量。键不区分大小写。
     */
    private Map<String, Dependency> dependencies = new LinkedHashMap<>();

    /**
     * 单个依赖的镜像配置。
     */
    @Data
    public static class Dependency {

        /**
         * 镜像，例如 mysql:8.0。
         */
        private String image;

        /**
         * 容器内监听端口。
         */
        private Integer port;

        /**
         * 容器环境变量。值可能是密码，不进入 toString。
         */
        @ToString.Exclude
        private Map<String, String> env = new LinkedHashMap<>();
    }
}
