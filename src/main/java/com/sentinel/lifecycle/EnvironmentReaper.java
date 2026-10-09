package com.sentinel.lifecycle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sentinel.config.properties.LifecycleProperties;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.domain.Environment;
import com.sentinel.mapper.EnvironmentMapper;
import com.sentinel.service.EnvironmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 释放到期环境，并补扫创建后长时间停在 CREATING 的环境。
 * 调度间隔来自 sentinel.lifecycle，首次执行延后一个间隔，避免应用刚启动就碰 Docker。
 *
 * @author Sentinel Team
 */
@Slf4j
@Component
public class EnvironmentReaper {

    /** 与 {@link ProvisioningProperties#getReadyTimeout()} 的默认值一致。 */
    static final Duration DEFAULT_READY_TIMEOUT = Duration.ofSeconds(90);

    private static final String STATUS_IDLE = "IDLE";
    private static final String STATUS_RELEASING = "RELEASING";
    private static final String STATUS_CREATING = "CREATING";

    private final LifecycleProperties lifecycleProperties;
    private final EnvironmentService environmentService;
    private final EnvironmentMapper environmentMapper;
    private final TaskLifecycleOrchestrator taskLifecycleOrchestrator;
    private final Duration readyTimeout;
    private final Clock clock;

    @Autowired
    public EnvironmentReaper(LifecycleProperties lifecycleProperties,
                             EnvironmentService environmentService,
                             EnvironmentMapper environmentMapper,
                             TaskLifecycleOrchestrator taskLifecycleOrchestrator,
                             ProvisioningProperties provisioningProperties) {
        this(lifecycleProperties, environmentService, environmentMapper, taskLifecycleOrchestrator,
                provisioningProperties == null ? null : provisioningProperties.getReadyTimeout(),
                Clock.systemDefaultZone());
    }

    EnvironmentReaper(LifecycleProperties lifecycleProperties,
                      EnvironmentService environmentService,
                      EnvironmentMapper environmentMapper,
                      TaskLifecycleOrchestrator taskLifecycleOrchestrator,
                      Duration readyTimeout,
                      Clock clock) {
        this.lifecycleProperties = lifecycleProperties;
        this.environmentService = environmentService;
        this.environmentMapper = environmentMapper;
        this.taskLifecycleOrchestrator = taskLifecycleOrchestrator;
        this.readyTimeout = readyTimeout;
        this.clock = clock;
    }

    /**
     * 释放 expiresAt 已过且不是 IDLE/RELEASING 的环境（含 ERROR，便于失败后重试）。
     * CREATING 超过两倍就绪超时的，先把 expiresAt 拨到过去再释放。
     */
    @Scheduled(
            fixedDelayString = "#{@lifecycleProperties.reaperInterval.toMillis()}",
            initialDelayString = "#{@lifecycleProperties.reaperInterval.toMillis()}")
    public void reap() {
        if (!isPositive(lifecycleProperties.getReaperInterval())) {
            log.debug("环境回收间隔未启用，跳过");
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Duration timeout = effectiveReadyTimeout();
        LocalDateTime stuckBefore = now.minus(timeout.multipliedBy(2));
        markStuckCreating(now, stuckBefore);

        List<Environment> expired = environmentMapper.selectList(new LambdaQueryWrapper<Environment>()
                .lt(Environment::getExpiresAt, now)
                .notIn(Environment::getStatus, STATUS_IDLE, STATUS_RELEASING));
        List<Environment> stuck = environmentMapper.selectList(new LambdaQueryWrapper<Environment>()
                .eq(Environment::getStatus, STATUS_CREATING)
                .lt(Environment::getCreatedAt, stuckBefore));

        List<Environment> candidates = new ArrayList<>();
        if (expired != null) {
            candidates.addAll(expired);
        }
        if (stuck != null) {
            candidates.addAll(stuck);
        }
        releaseEach(candidates);
    }

    /**
     * 自动供给开启时，把遗漏的 PENDING 任务交回编排器。
     */
    @Scheduled(
            fixedDelayString = "#{@lifecycleProperties.pendingSweepInterval.toMillis()}",
            initialDelayString = "#{@lifecycleProperties.pendingSweepInterval.toMillis()}")
    public void sweepStalePendingTasks() {
        if (!lifecycleProperties.isAutoProvision() || !isPositive(lifecycleProperties.getPendingSweepInterval())) {
            return;
        }
        taskLifecycleOrchestrator.sweepPending();
    }

    /**
     * 卡在 CREATING 的环境可能没有 expiresAt。拨到当前时间之前，释放失败变成 ERROR 后下一轮仍能扫到。
     */
    private void markStuckCreating(LocalDateTime now, LocalDateTime stuckBefore) {
        environmentMapper.update(null, new LambdaUpdateWrapper<Environment>()
                .set(Environment::getExpiresAt, now.minusSeconds(1))
                .eq(Environment::getStatus, STATUS_CREATING)
                .lt(Environment::getCreatedAt, stuckBefore)
                .and(nested -> nested.isNull(Environment::getExpiresAt)
                        .or()
                        .gt(Environment::getExpiresAt, now)));
    }

    private void releaseEach(List<Environment> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        Set<Long> seen = new HashSet<>();
        int released = 0;
        for (Environment environment : candidates) {
            if (environment == null || environment.getId() == null || !seen.add(environment.getId())) {
                continue;
            }
            String status = environment.getStatus();
            if (STATUS_IDLE.equals(status) || STATUS_RELEASING.equals(status)) {
                continue;
            }
            try {
                environmentService.releaseEnvironment(environment.getId());
                released++;
            } catch (RuntimeException ex) {
                log.warn("回收环境失败，下个周期再试: envId={}, status={}, reason={}",
                        environment.getId(), status, ex.getMessage());
            }
        }
        if (released > 0) {
            log.info("已回收过期环境: count={}", released);
        }
    }

    private Duration effectiveReadyTimeout() {
        if (!isPositive(readyTimeout)) {
            return DEFAULT_READY_TIMEOUT;
        }
        return readyTimeout;
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
