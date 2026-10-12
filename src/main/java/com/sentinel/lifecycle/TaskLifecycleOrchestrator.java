package com.sentinel.lifecycle;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sentinel.config.BusinessException;
import com.sentinel.config.LifecycleAsyncConfig;
import com.sentinel.config.properties.LifecycleProperties;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Task;
import com.sentinel.event.EnvironmentReleasedEvent;
import com.sentinel.event.TaskCreatedEvent;
import com.sentinel.event.TaskStatusChangedEvent;
import com.sentinel.mapper.EnvironmentMapper;
import com.sentinel.service.DiagnosisService;
import com.sentinel.service.EnvironmentProvisioner;
import com.sentinel.service.EnvironmentService;
import com.sentinel.service.ProvisioningCapacity;
import com.sentinel.service.QueueOrder;
import com.sentinel.service.TaskService;
import com.sentinel.service.provision.ProvisioningFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 任务创建后供给环境，终态后释放或保留现场。
 * 监听器在事务提交后异步执行。容量够时 PENDING 到 QUEUED 的比较并设置保证只供给一次；
 * 容量不够时同样写成 QUEUED，但带 queuedAt，表示在排队、还没有环境。
 * 出队把这种 QUEUED 改回 PENDING（并清空 queuedAt）再供给。进程内锁串行判断上限。
 * 多实例共享数据库时这把锁无效，需要分布式锁（可用 CacheService 的 Redis）。
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
    private final ProvisioningCapacity provisioningCapacity;
    private final Executor lifecycleExecutor;
    private final Clock clock;
    /**
     * 单机准入锁。多实例各自一把，限制会被同时突破，需要分布式锁。
     */
    private final ReentrantLock admissionLock = new ReentrantLock();
    private final ThreadLocal<Integer> drainDepth = ThreadLocal.withInitial(() -> 0);

    @Autowired
    public TaskLifecycleOrchestrator(TaskService taskService,
                                     EnvironmentService environmentService,
                                     DiagnosisService diagnosisService,
                                     EnvironmentProvisioner environmentProvisioner,
                                     EnvironmentMapper environmentMapper,
                                     LifecycleProperties lifecycleProperties,
                                     ProvisioningProperties provisioningProperties,
                                     ProvisioningCapacity provisioningCapacity,
                                     @Qualifier(LifecycleAsyncConfig.LIFECYCLE_EXECUTOR) Executor lifecycleExecutor) {
        this(taskService, environmentService, diagnosisService, environmentProvisioner, environmentMapper,
                lifecycleProperties, provisioningProperties, provisioningCapacity, lifecycleExecutor,
                Clock.systemDefaultZone());
    }

    TaskLifecycleOrchestrator(TaskService taskService,
                              EnvironmentService environmentService,
                              DiagnosisService diagnosisService,
                              EnvironmentProvisioner environmentProvisioner,
                              EnvironmentMapper environmentMapper,
                              LifecycleProperties lifecycleProperties,
                              ProvisioningProperties provisioningProperties,
                              ProvisioningCapacity provisioningCapacity,
                              Executor lifecycleExecutor,
                              Clock clock) {
        this.taskService = taskService;
        this.environmentService = environmentService;
        this.diagnosisService = diagnosisService;
        this.environmentProvisioner = environmentProvisioner;
        this.environmentMapper = environmentMapper;
        this.lifecycleProperties = lifecycleProperties;
        this.provisioningProperties = provisioningProperties;
        this.provisioningCapacity = provisioningCapacity;
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
     * 成功或取消立即释放；失败先诊断（内部会同步采集容器日志），
     * 再把仍是 BUSY 或 READY 的环境标为 RETAINED 并缩短过期时间。
     * 若该更新未命中，只缩短仍为 ERROR 的环境的过期时间，不改状态。
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
                // QUEUED、RUNNING、出队时短暂的 PENDING 不在这里改环境
            }
        }
        scheduleDrain();
    }

    /**
     * 环境变为 IDLE 后尝试出队。提交到生命周期线程池，避免释放接口等到容器启动完。
     */
    @EventListener
    public void onEnvironmentReleased(EnvironmentReleasedEvent event) {
        scheduleDrain();
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
        LocalDateTime cutoff = now().minus(PENDING_MIN_AGE);
        int submitted = 0;
        if (pending != null) {
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
        }
        if (submitted > 0) {
            log.info("已提交遗漏的 PENDING 任务: count={}", submitted);
        }
        scheduleDrain();
    }

    /**
     * 容量够且没有更高优先级的排队任务时，比较并设置 PENDING→QUEUED 再供给。
     * 否则写成带 queuedAt 的 QUEUED，不创建环境。供给期间被取消时释放新环境。
     * 供给失败但异常带了环境编号时，先绑定再标失败，不立刻释放。环境保持 ERROR。
     */
    void provisionTask(Long taskId) {
        if (!lifecycleProperties.isAutoProvision()) {
            log.debug("自动供给已关闭，跳过: taskId={}", taskId);
            return;
        }
        if (taskId == null) {
            return;
        }
        boolean admitted = false;
        admissionLock.lock();
        try {
            if (shouldQueue(taskId)) {
                taskService.markQueued(taskId, now());
            } else if (taskService.transitionStatus(taskId, "PENDING", "QUEUED")) {
                provisioningCapacity.reserve(taskId, provisioningCapacity.containersNeeded(taskId));
                admitted = true;
            } else {
                log.info("任务未处于 PENDING，跳过供给: taskId={}", taskId);
            }
        } finally {
            admissionLock.unlock();
        }
        if (!admitted) {
            scheduleDrain();
            return;
        }
        try {
            provisionAfterClaim(taskId);
        } finally {
            provisioningCapacity.release(taskId);
            scheduleDrain();
        }
    }

    /**
     * 调用方已经把任务认领为供给中的 QUEUED（queuedAt 为空）并占住容量。
     */
    private void provisionAfterClaim(Long taskId) {
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
            // 环境行已经落库。先绑定再标失败。FAILED 事件不把 ERROR 改成 RETAINED，只缩短过期时间。
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
     * 按优先级和排队时间出队，能放下多少就启动多少。
     * 同一任务并发出队时，QUEUED→PENDING 的条件更新只有一个成功。
     */
    void drainQueue() {
        if (!lifecycleProperties.isAutoProvision()) {
            return;
        }
        if (drainDepth.get() > 0) {
            return;
        }
        drainDepth.set(1);
        try {
            while (true) {
                List<Long> claimed = claimWaiting();
                if (claimed.isEmpty()) {
                    return;
                }
                log.info("开始出队: count={}", claimed.size());
                for (Long taskId : claimed) {
                    try {
                        provisionAfterClaim(taskId);
                    } finally {
                        provisioningCapacity.release(taskId);
                    }
                }
            }
        } finally {
            drainDepth.set(0);
        }
    }

    private boolean shouldQueue(Long taskId) {
        if (provisioningCapacity.wouldExceed(taskId)) {
            return true;
        }
        List<Task> waiting = taskService.listWaitingQueued();
        if (waiting == null || waiting.isEmpty()) {
            return false;
        }
        Task candidate = taskService.lookupTask(taskId);
        if (candidate == null) {
            return true;
        }
        for (Task queued : waiting) {
            if (QueueOrder.outranks(queued, candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在准入锁里认领所有当前放得下的排队任务。供给中的 QUEUED 不会被认领。
     */
    private List<Long> claimWaiting() {
        admissionLock.lock();
        try {
            List<Task> waiting = taskService.listWaitingQueued();
            if (waiting == null || waiting.isEmpty()) {
                return List.of();
            }
            List<Task> ordered = new ArrayList<>();
            for (Task task : waiting) {
                if (task != null && task.getId() != null) {
                    ordered.add(task);
                }
            }
            ordered.sort(QueueOrder.COMPARATOR);
            List<Long> claimed = new ArrayList<>();
            for (Task task : ordered) {
                if (provisioningCapacity.wouldExceed(task.getId())) {
                    continue;
                }
                if (!taskService.claimQueued(task.getId())) {
                    continue;
                }
                if (!taskService.transitionStatus(task.getId(), "PENDING", "QUEUED")) {
                    log.info("出队后未能进入供给: taskId={}", task.getId());
                    continue;
                }
                provisioningCapacity.reserve(task.getId(), provisioningCapacity.containersNeeded(task.getId()));
                claimed.add(task.getId());
            }
            return claimed;
        } finally {
            admissionLock.unlock();
        }
    }

    private void scheduleDrain() {
        if (!lifecycleProperties.isAutoProvision()) {
            return;
        }
        submit(this::drainQueue);
    }

    /**
     * 失败先诊断，再把仍是 BUSY 或 READY 的环境标为 RETAINED，过期时间改为失败保留时长。
     * 供给失败留下的 ERROR 保持原状态，只缩短过期时间。RELEASING、IDLE 不改。不释放环境。
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
        // 只改 BUSY/READY。RELEASING、IDLE、ERROR（含供给失败）保持原状态。
        LambdaUpdateWrapper<Environment> wrapper = new LambdaUpdateWrapper<Environment>()
                .set(Environment::getStatus, "RETAINED")
                .set(Environment::getExpiresAt, expiresAt)
                .eq(Environment::getId, task.getEnvironmentId())
                .in(Environment::getStatus, "BUSY", "READY");
        int updated = environmentMapper.update(null, wrapper);
        if (updated > 0) {
            log.info("失败环境已保留待查: taskId={}, envId={}, expiresAt={}",
                    task.getId(), task.getEnvironmentId(), expiresAt);
            return;
        }
        // 供给失败时环境已是 ERROR，保留原状态，只把过期时间缩短到失败保留时长。
        // RELEASING、IDLE 不匹配，保持不动。
        LambdaUpdateWrapper<Environment> errorExpiry = new LambdaUpdateWrapper<Environment>()
                .set(Environment::getExpiresAt, expiresAt)
                .eq(Environment::getId, task.getEnvironmentId())
                .eq(Environment::getStatus, "ERROR");
        int errorUpdated = environmentMapper.update(null, errorExpiry);
        if (errorUpdated == 0) {
            log.info("失败环境未改为保留（当前不是 BUSY、READY 或 ERROR）: taskId={}, envId={}",
                    task.getId(), task.getEnvironmentId());
            return;
        }
        log.info("供给失败环境已缩短过期时间，状态仍为 ERROR: taskId={}, envId={}, expiresAt={}",
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
