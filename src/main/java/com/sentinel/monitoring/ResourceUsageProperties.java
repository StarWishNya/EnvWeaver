package com.sentinel.monitoring;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 容器 CPU / 内存采集配置。
 * 定时任务的 fixedDelay 通过 SpEL 读本 bean（名称必须是 resourceUsageProperties），
 * Spring 6.0 不能把 fixedDelayString 里的 30s 解析成时长。
 *
 * @author Sentinel Team
 */
@Data
@Validated
@Component("resourceUsageProperties")
@ConfigurationProperties(prefix = "sentinel.monitoring.resource-usage")
public class ResourceUsageProperties {

    /**
     * 是否采集运行中容器的 CPU 和内存。测试环境关闭，避免访问 Docker。
     */
    private boolean enabled = true;

    /**
     * 采集间隔。首次执行也延后一个间隔。
     */
    @NotNull
    private Duration interval = Duration.ofSeconds(30);

    /**
     * 单次 docker stats 的等待上限。容器无响应时到点返回空，不堵住后续采集。
     */
    @NotNull
    private Duration readTimeout = Duration.ofSeconds(5);

    /**
     * 每一轮最多处理的环境数。采样和清空空闲环境各用这个上限。
     */
    @Min(1)
    private int maxEnvironmentsPerPass = 200;

    /**
     * 同一环境内并行读取容器统计的线程数。线程池在采集器创建时确定。
     */
    @Min(1)
    private int parallelism = 4;
}
