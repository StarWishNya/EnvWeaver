package com.sentinel.lifecycle;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sentinel.config.properties.LifecycleProperties;
import com.sentinel.domain.Container;
import com.sentinel.domain.Environment;
import com.sentinel.event.EnvironmentReleasedEvent;
import com.sentinel.mapper.ContainerMapper;
import com.sentinel.mapper.EnvironmentMapper;
import com.sentinel.service.TaskService;
import com.sentinel.service.provision.ContainerLauncher;
import com.sentinel.service.provision.DockerMessages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 把容器已经不在的 READY、BUSY、RETAINED 环境标成 ERROR，让它们不再占供给名额。
 * 调度间隔来自 sentinel.lifecycle.reconcile，首次执行延后一个间隔。
 *
 * @author Sentinel Team
 */
@Slf4j
@Component
public class EnvironmentReconciler {

    /**
     * 会核对的环境状态。CREATING 还在启动，RELEASING 正在删容器，都不碰。
     */
    static final List<String> RECONCILE_STATUSES = List.of("READY", "BUSY", "RETAINED");

    static final int SCAN_LIMIT = 500;

    /**
     * 写进任务 errorMessage，也写在环境 composeContent 开头。
     */
    static final String MISSING_REASON = "环境容器已不存在（可能被手动删除或主机重启）";

    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_STOPPED = "STOPPED";

    private final LifecycleProperties lifecycleProperties;
    private final EnvironmentMapper environmentMapper;
    private final ContainerMapper containerMapper;
    private final ContainerLauncher containerLauncher;
    private final TaskService taskService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public EnvironmentReconciler(LifecycleProperties lifecycleProperties,
                                 EnvironmentMapper environmentMapper,
                                 ContainerMapper containerMapper,
                                 ContainerLauncher containerLauncher,
                                 TaskService taskService,
                                 ApplicationEventPublisher eventPublisher) {
        this(lifecycleProperties, environmentMapper, containerMapper, containerLauncher,
                taskService, eventPublisher, Clock.systemDefaultZone());
    }

    EnvironmentReconciler(LifecycleProperties lifecycleProperties,
                          EnvironmentMapper environmentMapper,
                          ContainerMapper containerMapper,
                          ContainerLauncher containerLauncher,
                          TaskService taskService,
                          ApplicationEventPublisher eventPublisher,
                          Clock clock) {
        this.lifecycleProperties = lifecycleProperties;
        this.environmentMapper = environmentMapper;
        this.containerMapper = containerMapper;
        this.containerLauncher = containerLauncher;
        this.taskService = taskService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * 容器全部缺失、一个都没有，或只缺一部分时，环境都不可用。
     * Docker 出现 NotFound 以外的错误则本轮不动该环境，整轮只记一次 WARN。
     */
    @Scheduled(
            fixedDelayString = "#{@lifecycleProperties.reconcile.interval.toMillis()}",
            initialDelayString = "#{@lifecycleProperties.reconcile.interval.toMillis()}")
    public void reconcile() {
        if (!shouldRun()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Environment> environments;
        try {
            environments = environmentMapper.selectByStatuses(RECONCILE_STATUSES, SCAN_LIMIT);
        } catch (RuntimeException ex) {
            log.warn("查询待核对环境失败，本轮跳过: reason={}", ex.getMessage());
            return;
        }
        if (environments == null || environments.isEmpty()) {
            return;
        }
        boolean warned = false;
        int changed = 0;
        for (Environment environment : environments) {
            try {
                Outcome outcome = reconcileOne(environment, now, warned);
                warned = outcome.warned();
                if (outcome.changed()) {
                    changed++;
                }
            } catch (RuntimeException ex) {
                log.warn("核对环境失败，下个周期再试: envId={}, reason={}",
                        environment == null ? null : environment.getId(), ex.getMessage());
            }
        }
        if (changed > 0) {
            log.info("已核对缺失容器的环境: count={}", changed);
        }
    }

    private Outcome reconcileOne(Environment environment, LocalDateTime now, boolean warned) {
        if (environment == null || environment.getId() == null) {
            return new Outcome(false, warned);
        }
        String status = environment.getStatus();
        if (status == null || !RECONCILE_STATUSES.contains(status)) {
            return new Outcome(false, warned);
        }
        if (inGrace(environment, now)) {
            log.debug("环境仍在宽限期内，跳过核对: envId={}", environment.getId());
            return new Outcome(false, warned);
        }
        List<Container> containers;
        try {
            containers = containerMapper.findByEnvironmentId(environment.getId());
        } catch (RuntimeException ex) {
            return new Outcome(false, warnOnce(warned, environment.getId(), null, ex));
        }
        if (containers == null) {
            return new Outcome(false, warnOnce(warned, environment.getId(), null,
                    new IllegalStateException("查询容器失败")));
        }

        List<Container> tracked = new ArrayList<>();
        for (Container container : containers) {
            if (container != null) {
                tracked.add(container);
            }
        }
        List<Container> missing = new ArrayList<>();
        for (Container container : tracked) {
            try {
                if (!containerLauncher.exists(container.getContainerId())) {
                    missing.add(container);
                }
            } catch (RuntimeException ex) {
                return new Outcome(false, warnOnce(warned, environment.getId(), container.getContainerId(), ex));
            }
        }
        if (tracked.isEmpty()) {
            if (isIntentionalEmpty(environment)) {
                log.debug("无依赖空环境，跳过核对: envId={}", environment.getId());
                return new Outcome(false, warned);
            }
        } else if (missing.isEmpty()) {
            return new Outcome(false, warned);
        }
        boolean updated = markMissing(environment, missing, now);
        return new Outcome(updated, warned);
    }

    /**
     * 没有依赖的任务会落一条 {@code services: {}} 且没有容器行。那不是容器丢失。
     * 编排里写了服务，或端口映射不是空对象，却没有任何容器行，仍然按全部缺失处理。
     */
    static boolean isIntentionalEmpty(Environment environment) {
        if (environment == null || !isEmptyCompose(environment.getComposeContent())) {
            return false;
        }
        String ports = environment.getPortMappings();
        return ports == null || ports.isBlank() || "{}".equals(ports.trim());
    }

    private static boolean isEmptyCompose(String compose) {
        return compose != null && "services: {}".equals(compose.trim());
    }

    /**
     * 条件更新未命中时不改容器、不改任务、不发事件。
     * 命中后无论容器或任务写入是否失败，都发布释放事件，让队列可以出队。
     */
    private boolean markMissing(Environment environment, List<Container> missing, LocalDateTime now) {
        String compose = composeWithReason(environment.getComposeContent());
        int updated = environmentMapper.update(null, new LambdaUpdateWrapper<Environment>()
                .set(Environment::getStatus, STATUS_ERROR)
                .set(Environment::getExpiresAt, now)
                .set(Environment::getComposeContent, compose)
                .setSql("port_mappings = NULL")
                .setSql("resource_usage = NULL")
                .eq(Environment::getId, environment.getId())
                .eq(Environment::getStatus, environment.getStatus()));
        if (updated == 0) {
            log.info("环境状态已变化，本轮不改任务: envId={}, status={}",
                    environment.getId(), environment.getStatus());
            return false;
        }
        try {
            markStopped(missing, now);
        } catch (RuntimeException ex) {
            log.warn("缺失容器标为 STOPPED 失败: envId={}, reason={}", environment.getId(), ex.getMessage());
        }
        try {
            failRunningTask(environment);
        } catch (RuntimeException ex) {
            log.warn("容器缺失后标记任务失败未成功: envId={}, taskId={}, reason={}",
                    environment.getId(), environment.getTaskId(), ex.getMessage());
        }
        eventPublisher.publishEvent(new EnvironmentReleasedEvent(environment.getId()));
        log.info("环境容器已不存在，已标为 ERROR: envId={}, status={}, missing={}",
                environment.getId(), environment.getStatus(), missing.size());
        return true;
    }

    private void markStopped(List<Container> missing, LocalDateTime now) {
        for (Container container : missing) {
            if (container.getId() == null) {
                log.warn("容器记录没有主键，无法标为 STOPPED: environmentId={}, containerId={}",
                        container.getEnvironmentId(), container.getContainerId());
                continue;
            }
            container.setStatus(STATUS_STOPPED);
            container.setStoppedAt(now);
            container.setLastLog(MISSING_REASON);
            containerMapper.updateById(container);
        }
    }

    private void failRunningTask(Environment environment) {
        Long taskId = environment.getTaskId();
        if (taskId == null) {
            return;
        }
        taskService.failIfRunning(taskId, MISSING_REASON);
    }

    private boolean warnOnce(boolean warned, Long environmentId, String containerId, RuntimeException ex) {
        if (warned) {
            return true;
        }
        log.warn("核对容器是否存在失败，本轮不改环境: envId={}, containerId={}, reason={}",
                environmentId, containerId, reasonOf(ex));
        return true;
    }

    private boolean inGrace(Environment environment, LocalDateTime now) {
        Duration grace = lifecycleProperties.getReconcile().getGrace();
        if (!isPositive(grace)) {
            return false;
        }
        LocalDateTime cutoff = now.minus(grace);
        return isAfter(environment.getCreatedAt(), cutoff) || isAfter(environment.getUpdatedAt(), cutoff);
    }

    private boolean shouldRun() {
        if (lifecycleProperties == null || lifecycleProperties.getReconcile() == null) {
            return false;
        }
        LifecycleProperties.Reconcile reconcile = lifecycleProperties.getReconcile();
        if (!reconcile.isEnabled()) {
            log.debug("容器核对未启用，跳过");
            return false;
        }
        if (!isPositive(reconcile.getInterval())) {
            log.debug("容器核对间隔未启用，跳过");
            return false;
        }
        return true;
    }

    static String composeWithReason(String composeContent) {
        String note = "# " + MISSING_REASON;
        if (composeContent == null || composeContent.isBlank()) {
            return note;
        }
        if (composeContent.stripLeading().startsWith(note)) {
            return composeContent;
        }
        return note + "\n" + composeContent;
    }

    private static String reasonOf(RuntimeException ex) {
        String sanitized = DockerMessages.sanitize(ex.getMessage());
        if (sanitized != null) {
            return sanitized;
        }
        return ex.getClass().getSimpleName();
    }

    private static boolean isAfter(LocalDateTime value, LocalDateTime cutoff) {
        return value != null && value.isAfter(cutoff);
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private record Outcome(boolean changed, boolean warned) {
    }
}
