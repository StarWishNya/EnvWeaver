package com.sentinel.integration;

import com.sentinel.config.BusinessException;
import com.sentinel.config.LifecycleAsyncConfig;
import com.sentinel.domain.Task;
import com.sentinel.event.TaskStatusChangedEvent;
import com.sentinel.mapper.TaskMapper;
import com.sentinel.service.TaskService;
import com.sentinel.testutil.TaskBuilder;
import com.sentinel.testutil.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取消和运行中失败同时写同一行。连接测试 profile 的 MySQL，不用类级事务，
 * 让两个线程各自提交。只删除本用例插入的任务。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "logging.level.com.sentinel=DEBUG"
})
@DisplayName("任务状态并发")
class TaskStatusRaceIntegrationTest {

    private static final String FAIL_MESSAGE = "race-fail";
    private static final int TASKS = 30;

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ApplicationEventMulticaster multicaster;

    @Autowired
    @Qualifier(LifecycleAsyncConfig.LIFECYCLE_EXECUTOR)
    private ThreadPoolTaskExecutor lifecycleExecutor;

    private final List<Long> taskIds = new ArrayList<>();
    private final Set<Long> trackedIds = ConcurrentHashMap.newKeySet();
    private final List<TaskStatusChangedEvent> events = new CopyOnWriteArrayList<>();

    private final ApplicationListener<ApplicationEvent> listener = new ApplicationListener<ApplicationEvent>() {
        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            Object payload = event;
            if (event instanceof PayloadApplicationEvent<?> payloadEvent) {
                payload = payloadEvent.getPayload();
            }
            if (payload instanceof TaskStatusChangedEvent statusEvent && trackedIds.contains(statusEvent.taskId())) {
                events.add(statusEvent);
            }
        }
    };

    @AfterEach
    void deleteCreatedTasks() {
        for (Long taskId : new ArrayList<>(taskIds)) {
            taskMapper.deleteById(taskId);
        }
        taskIds.clear();
        trackedIds.clear();
        events.clear();
    }

    @Test
    @DisplayName("30 个 RUNNING 任务上取消和 failIfRunning 并发，每个任务只落一种终态")
    void cancelAndFailIfRunningDoNotOverwriteEachOther() throws Exception {
        multicaster.addApplicationListener(listener);
        try {
            String token = TestUtils.uniqueToken();
            for (int i = 0; i < TASKS; i++) {
                Task task = TaskBuilder.aTask()
                        .withTaskCode(taskCode(token, i))
                        .withEnvironmentId(null)
                        .withErrorMessage(null)
                        .running()
                        .build();
                assertThat(taskMapper.insert(task)).isEqualTo(1);
                assertThat(task.getId()).isNotNull();
                taskIds.add(task.getId());
                trackedIds.add(task.getId());
            }

            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Boolean> cancelSucceeded = new ArrayList<>();
            List<Boolean> failed = new ArrayList<>();
            try {
                for (Long taskId : taskIds) {
                    CountDownLatch ready = new CountDownLatch(2);
                    CountDownLatch start = new CountDownLatch(1);
                    Future<Boolean> cancelFuture = pool.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("cancel did not start");
                        }
                        try {
                            taskService.cancelTask(taskId);
                            return true;
                        } catch (BusinessException ex) {
                            return false;
                        }
                    });
                    Future<Boolean> failFuture = pool.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("failIfRunning did not start");
                        }
                        return taskService.failIfRunning(taskId, FAIL_MESSAGE);
                    });
                    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                    start.countDown();
                    cancelSucceeded.add(cancelFuture.get(20, TimeUnit.SECONDS));
                    failed.add(failFuture.get(20, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }
            drainLifecycle();

            assertThat(events).hasSize(TASKS);
            for (int i = 0; i < taskIds.size(); i++) {
                Long taskId = taskIds.get(i);
                Task stored = taskMapper.selectById(taskId);
                assertThat(stored).isNotNull();
                assertThat(stored.getStatus()).isIn("CANCELLED", "FAILED");
                if (cancelSucceeded.get(i)) {
                    assertThat(stored.getStatus()).isEqualTo("CANCELLED");
                }
                if ("FAILED".equals(stored.getStatus())) {
                    assertThat(cancelSucceeded.get(i)).isFalse();
                    assertThat(failed.get(i)).isTrue();
                    assertThat(stored.getErrorMessage()).isEqualTo(FAIL_MESSAGE);
                } else {
                    assertThat(stored.getErrorMessage()).isNull();
                    assertThat(failed.get(i)).isFalse();
                }
                List<TaskStatusChangedEvent> taskEvents = events.stream()
                        .filter(event -> taskId.equals(event.taskId()))
                        .toList();
                assertThat(taskEvents).hasSize(1);
                assertThat(taskEvents.get(0).oldStatus()).isEqualTo("RUNNING");
                assertThat(taskEvents.get(0).newStatus()).isEqualTo(stored.getStatus());
            }
        } finally {
            multicaster.removeApplicationListener(listener);
        }
    }

    private static String taskCode(String token, int index) {
        return "RACE-" + token + "-" + index;
    }

    private void drainLifecycle() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ThreadPoolExecutor pool = lifecycleExecutor.getThreadPoolExecutor();
        while (System.nanoTime() < deadline) {
            if (pool.getActiveCount() == 0 && pool.getQueue().isEmpty()) {
                return;
            }
            Thread.sleep(20);
        }
    }
}
