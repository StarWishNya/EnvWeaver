package com.sentinel.config.properties;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 任务生命周期配置。
 * 定时任务的 fixedDelay 通过 SpEL 读取本 bean（名称必须是 lifecycleProperties）。
 *
 * @author Sentinel Team
 */
@Data
@Validated
@Component("lifecycleProperties")
@ConfigurationProperties(prefix = "sentinel.lifecycle")
public class LifecycleProperties {

    /**
     * 创建任务后是否自动供给环境。测试环境关闭，避免碰 Docker。
     */
    private boolean autoProvision = true;

    /**
     * 任务失败后是否自动请求诊断。测试环境关闭，避免碰 AI。
     */
    private boolean autoDiagnose = true;

    /**
     * 失败环境保留时长。到期后由回收器释放，便于查看现场。
     */
    @NotNull
    private Duration failedRetention = Duration.ofMinutes(30);

    /**
     * 补扫遗漏 PENDING 任务的间隔。
     */
    @NotNull
    private Duration pendingSweepInterval = Duration.ofSeconds(30);

    /**
     * 过期环境回收间隔。
     */
    @NotNull
    private Duration reaperInterval = Duration.ofSeconds(60);
}
