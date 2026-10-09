package com.sentinel.lifecycle;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sentinel.config.BusinessException;
import com.sentinel.config.LifecycleAsyncConfig;
import com.sentinel.config.properties.LifecycleProperties;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Task;
import com.sentinel.event.TaskCreatedEvent;
import com.sentinel.event.TaskStatusChangedEvent;
import com.sentinel.mapper.EnvironmentMapper;
import com.sentinel.service.DiagnosisService;
import com.sentinel.service.EnvironmentProvisioner;
import com.sentinel.service.EnvironmentService;
import com.sentinel.service.TaskService;
import com.sentinel.service.provision.ProvisioningFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 任务创建后供给环境，终态后释放或保留现场。
 * 监听器在事务提交后异步执行；PENDING 到 QUEUED 的比较并设置保证只供给一次。
 *
 * @author Sentinel Team
 */
@Slf4j
@Component
public class TaskLifecycleOrchestrator {

    /**
     * 创建事件可能还在队列里。补扫只处理创建超过该时长的 PENDING，避免和事件抢跑。
     */
    static final Duration PENDING_MIN_AGE = Duration.ofSeconds(30);

    static final int PENDING_BATCH_LIMIT = 20;

    private static final int ERROR_REASON_MAX = 200;

    /** 与 {@link ProvisioningProperties#getTtl()} 的默认值一致，仅在该配置缺失时使用。 */
    private static final Duration DEFAULT_PROVISION_TTL = Duration.ofHours(2);

    private static final Duration DEFAULT_FAILED_RETENTION = Duration.ofMinutes(30);

    private final TaskService taskService;
    private final EnvironmentService environmentService;
    private final DiagnosisService diagnosisService;
    private final EnvironmentProvisioner environmentProvisioner;
    private final EnvironmentMapper environmentMapper;
    private final LifecycleProperties lifecycleProperties;
    private final ProvisioningProperties provisioningProperties;
    private final Executor lifecycleExecutor;
    private final Clock clock;

    @Autowired
    public TaskLifecycleOrchestrator(TaskService taskService,
                                     EnvironmentService environmentService,
                                     DiagnosisService diagnosisService,
                                     EnvironmentProvisioner environmentProvisioner,
                                     EnvironmentMapper environmentMapper,
                                     LifecycleProperties lifecycleProperties,
                                     ProvisioningProperties provisioningProperties,
                                     @Qualifier(LifecycleAsyncConfig.LIFECYCLE_EXECUTOR) Executor lifecycleExecutor) {
        this(taskService, environmentService, diagnosisService, environmentProvisioner, environmentMapper,
                lifecycleProperties, provisioningProperties, lifecycleExecutor, Clock.systemDefaultZone());
    }

    TaskLifecycleOrchestrator(TaskService taskService,
                              EnvironmentService environmentService,
                              DiagnosisService diagnosisService,
                              EnvironmentProvisioner environmentProvisioner,
                              EnvironmentMapper environmentMapper,
                              LifecycleProperties lifecycleProperties,
                              ProvisioningProperties provisioningProperties,
                              Executor lifecycleExecutor,
                              Clock clock) {
        this.taskService = taskService;
        this.environmentService = environmentService;
        this.diagnosisService = diagnosisService;
        this.environmentProvisioner = environmentProvisioner;
        this.environmentMapper = environmentMapper;
        this.lifecycleProperties = lifecycleProperties;
        this.provisioningProperties = provisioningProperties;
        this.lifecycleExecutor = lifecycleExecutor;
        this.clock = clock;
    }

    /**
     * 事务提交后再供给。线程池拒绝时任务保持 PENDING，交给补扫。
     */
    @Async(LifecycleAsyncConfig.LIFECYCLE_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTaskCreated(TaskCreatedEvent event) {
        if (event == null || event.taskId() == null) {
            return;
        }
        provisionTask(event.taskId());
    }

    /**
     * 成功或取消立即释放；失败先诊断（内部会同步采集容器日志）再缩短过期时间。
     */
    @Async(LifecycleAsyncConfig.LIFECYCLE_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTaskStatusChanged(TaskStatusChangedEvent event) {
        if (event == null || event.taskId() == null || event.newStatus() == null) {
            return;
        }
        switch (event.newStatus()) {
            case "SUCCESS", "CANCELLED" -> releaseForTask(event.taskId());
            case "FAILED" -> onFailed(event.taskId());
            default -> {
                // QUEUED、RUNNING 不在这里处理
            }
        }
    }

    /**
     * 捞出创建超过 {@link #PENDING_MIN_AGE} 的 PENDING 任务，每轮最多 {@link #PENDING_BATCH_LIMIT} 条。
     * 每条提交到生命周期线程池；池满则跳过，下轮再试。
     */
    public void sweepPending() {
        if (!lifecycleProperties.isAutoProvision() || !isPositive(lifecycleProperties.getPendingSweepInterval())) {
            return;
        }
        List<Task> pending = taskService.listTasksByStatus("PENDING");
        if (pending == null || pending.isEmpty()) {
            return;
        }
        LocalDateTime cutoff = now().minus(PENDING_MIN_AGE);
        int submitted = 0;
        for (Task task : pending) {
            if (submitted >= PENDING_BATCH_LIMIT) {
                break;
            }
            if (task == null || task.getId() == null || task.getCreatedAt() == null) {
                continue;
            }
            if (!task.getCreatedAt().isBefore(cutoff)) {
                continue;
            }
            Long taskId = task.getId();
            submit(() -> provisionTask(taskId));
            submitted++;
        }
        if (submitted > 0) {
            log.info("已提交遗漏的 PENDING 任务: count={}", submitted);
        }
    }

    /**
     * 比较并设置 PENDING→QUEUED 成功才供给。供给期间被取消时释放新环境。
     * 供给失败但异常带了环境编号时，先绑定再标失败，不立刻释放。
     */
    void provisionTask(Long taskId) {
        if (!lifecycleProperties.isAutoProvision()) {
            log.debug("自动供给已关闭，跳过: taskId={}", taskId);
            return;
        }
        if (!taskService.transitionStatus(taskId, "PENDING", "QUEUED")) {
            log.info("任务未处于 PENDING，跳过供给: taskId={}", taskId);
            return;
        }

        Task task;
        try {
            task = taskService.getTaskById(taskId);
        } catch (BusinessException ex) {
            log.warn("供给时任务不存在: taskId={}, reason={}", taskId, ex.getMessage());
            return;
        }

        Environment environment;
        try {
            log.info("开始供给环境: taskId={}", taskId);
            environment = environmentProvisioner.provision(task);
        } catch (ProvisioningFailedException ex) {
            // 环境行已经落库。先绑定再标失败，FAILED 事件只缩短过期时间，不释放。
            log.warn("环境供给失败: taskId={}, envId={}, reason={}",
                    taskId, ex.getEnvironmentId(), ex.getMessage());
            attachFailedEnvironment(taskId, ex.getEnvironmentId());
            markTaskFailed(taskId, shortReason(ex));
            return;
        } catch (RuntimeException ex) {
            logProvisionFailure(taskId, ex);
            markTaskFailed(taskId, shortReason(ex));
            return;
        }
        if (environment == null || environment.getId() == null) {
            markTaskFailed(taskId, "环境供给未返回环境");
            return;
        }

        Task current;
        try {
            current = taskService.getTaskById(taskId);
        } catch (BusinessException ex) {
            log.warn("供给后任务不存在，释放环境: taskId={}, envId={}", taskId, environment.getId());
            releaseQuietly(environment.getId());
            return;
        }
        if (!"QUEUED".equals(current.getStatus())) {
            log.info("供给期间任务状态已变化，释放环境: taskId={}, status={}", taskId, current.getStatus());
            releaseQuietly(environment.getId());
            return;
        }

        try {
            taskService.attachEnvironment(taskId, environment.getId());
        } catch (RuntimeException ex) {
            log.warn("绑定环境失败，释放环境: taskId={}, envId={}, reason={}",
                    taskId, environment.getId(), ex.getMessage());
            releaseQuietly(environment.getId());
            markTaskFailed(taskId, shortReason(ex));
            return;
        }

        markBusy(environment);
        if (!taskService.transitionStatus(taskId, "QUEUED", "RUNNING")) {
            // 比较并设置失败说明状态已变（通常是取消），不能把环境留在占用中。
            log.info("任务未能进入 RUNNING，释放环境: taskId={}, envId={}", taskId, environment.getId());
            releaseQuietly(environment.getId());
            return;
        }
        log.info("任务进入 RUNNING: taskId={}, envId={}", taskId, environment.getId());
    }

    /**
     * 失败先诊断，再把过期时间改成失败保留时长。不释放环境。
     * 供给失败挂上的 ERROR 环境走同一条路，回收器到期后再清。
     */
    private void onFailed(Long taskId) {
        Task task;
        try {
            task = taskService.getTaskById(taskId);
        } catch (RuntimeException ex) {
            log.warn("处理失败任务时读取任务失败: taskId={}, reason={}", taskId, ex.getMessage());
            return;
        }
        if (lifecycleProperties.isAutoDiagnose()) {
            try {
                // requestDiagnosis 返回前会同步收集容器日志，因此必须发生在任何释放之前。
                diagnosisService.requestDiagnosis(taskId, null);
            } catch (RuntimeException ex) {
                log.warn("自动诊断失败，已忽略: taskId={}, reason={}", taskId, ex.getMessage());
            }
        }
        retainFailedEnvironment(task);
    }

    private void retainFailedEnvironment(Task task) {
        if (task.getEnvironmentId() == null) {
            return;
        }
        Duration retention = lifecycleProperties.getFailedRetention() == null
                ? DEFAULT_FAILED_RETENTION : lifecycleProperties.getFailedRetention();
        LocalDateTime expiresAt = now().plus(retention);
        LambdaUpdateWrapper<Environment> wrapper = new LambdaUpdateWrapper<Environment>()
                .set(Environment::getExpiresAt, expiresAt)
                .eq(Environment::getId, task.getEnvironmentId());
        environmentMapper.update(null, wrapper);
        log.info("失败环境已保留待查: taskId={}, envId={}, expiresAt={}",
                task.getId(), task.getEnvironmentId(), expiresAt);
    }

    private void releaseForTask(Long taskId) {
        Task task;
        try {
            task = taskService.getTaskById(taskId);
        } catch (RuntimeException ex) {
            log.warn("释放环境前读取任务失败: taskId={}, reason={}", taskId, ex.getMessage());
            return;
        }
        if (task.getEnvironmentId() == null) {
            return;
        }
        releaseQuietly(task.getEnvironmentId());
    }

    /**
     * 只把状态改成 BUSY。过期时间沿用供给器已经写入的值；供给器没写时才用 sentinel.provisioning.ttl。
     */
    private void markBusy(Environment environment) {
        LambdaUpdateWrapper<Environment> wrapper = new LambdaUpdateWrapper<Environment>()
                .set(Environment::getStatus, "BUSY")
                .eq(Environment::getId, environment.getId());
        LocalDateTime expiresAt = environment.getExpiresAt();
        if (expiresAt == null) {
            expiresAt = now().plus(provisioningTtl());
            wrapper.set(Environment::getExpiresAt, expiresAt);
        }
        int updated = environmentMapper.update(null, wrapper);
        if (updated == 0) {
            log.warn("标记环境占用未更新任何行: envId={}", environment.getId());
            return;
        }
        log.info("环境已标记占用: envId={}, expiresAt={}", environment.getId(), expiresAt);
    }

    private Duration provisioningTtl() {
        Duration ttl = provisioningProperties.getTtl();
        return ttl == null ? DEFAULT_PROVISION_TTL : ttl;
    }

    private void releaseQuietly(Long environmentId) {
        try {
            environmentService.releaseEnvironment(environmentId);
        } catch (BusinessException ex) {
            // 释放失败时环境会停在 ERROR，expiresAt 到期后回收器再试。
            log.warn("释放环境失败，等待回收器重试: envId={}, reason={}", environmentId, ex.getMessage());
        }
    }

    /**
     * 供给失败时把已创建的环境挂到任务上，便于之后从任务找到容器和日志。
     * 绑定失败也继续把任务标为失败，并且不释放环境。
     */
    private void attachFailedEnvironment(Long taskId, Long environmentId) {
        if (environmentId == null) {
            return;
        }
        try {
            taskService.attachEnvironment(taskId, environmentId);
        } catch (RuntimeException ex) {
            log.warn("供给失败后绑定环境失败，任务仍记为失败: taskId={}, envId={}, reason={}",
                    taskId, environmentId, ex.getMessage());
        }
    }

    private void markTaskFailed(Long taskId, String reason) {
        try {
            taskService.setTaskError(taskId, reason);
        } catch (RuntimeException ex) {
            log.warn("写入任务失败状态失败: taskId={}, reason={}", taskId, ex.getMessage());
        }
    }

    private void submit(Runnable work) {
        try {
            lifecycleExecutor.execute(work);
        } catch (RejectedExecutionException ex) {
            log.warn("生命周期任务被拒绝，留给下一轮扫尾: {}", ex.getMessage());
        }
    }

    private void logProvisionFailure(Long taskId, RuntimeException ex) {
        if (ex instanceof BusinessException) {
            log.warn("环境供给失败: taskId={}, reason={}", taskId, ex.getMessage());
            return;
        }
        log.warn("环境供给失败: taskId={}, reason={}", taskId, ex.getMessage(), ex);
    }

    private static String shortReason(Throwable ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return "环境供给失败";
        }
        String oneLine = message.replace('\r', ' ').replace('\n', ' ').trim();
        if (oneLine.length() <= ERROR_REASON_MAX) {
            return oneLine;
        }
        return oneLine.substring(0, ERROR_REASON_MAX);
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
