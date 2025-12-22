package com.sentinel.config.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Docker 配置属性
 */
@Data
@Validated
@ConfigurationProperties(prefix = "docker")
public class DockerProperties {

    /**
     * Docker 主机地址
     * 示例：unix:///var/run/docker.sock 或 tcp://localhost:2375
     */
    @NotBlank(message = "Docker 主机地址不能为空")
    private String host = "unix:///var/run/docker.sock";

    /**
     * Docker API 版本
     */
    private String apiVersion = "1.41";

    /**
     * 是否启用 TLS 验证
     */
    private Boolean tlsVerify = false;

    /**
     * TLS 证书路径（当 tlsVerify=true 时需要）
     */
    private String certPath;

    /**
     * 连接超时时间（秒）
     */
    @Min(value = 5, message = "连接超时时间不能少于5秒")
    private Integer connectionTimeout = 30;

    /**
     * 响应超时时间（秒）
     */
    @Min(value = 10, message = "响应超时时间不能少于10秒")
    private Integer responseTimeout = 60;

    /**
     * 容器配置
     */
    private ContainerConfig container = new ContainerConfig();

    /**
     * 容器配置
     */
    @Data
    public static class ContainerConfig {
        /**
         * 默认网络名称
         */
        private String defaultNetwork = "sentinel-network";

        /**
         * 容器名称前缀
         */
        private String namePrefix = "sentinel-";

        /**
         * 默认内存限制（MB）
         */
        @Min(value = 128, message = "内存限制不能少于128MB")
        private Integer memoryLimit = 512;

        /**
         * 默认 CPU 限制（核心数）
         */
        private Double cpuLimit = 1.0;

        /**
         * 容器自动删除（停止后）
         */
        private Boolean autoRemove = false;

        /**
         * 容器重启策略
         */
        private String restartPolicy = "unless-stopped";
    }
}
