package com.sentinel.dto;

import com.sentinel.domain.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 排队字段只在真正排队时出现。
 */
@DisplayName("任务响应排队字段")
class TaskResponseQueueTest {

    @Test
    @DisplayName("QUEUED 且有 queuedAt 时带 1 开始的位次")
    void queuedTaskExposesPosition() {
        Task task = task("QUEUED");
        task.setQueuedAt(LocalDateTime.of(2026, 10, 10, 1, 2, 3));

        TaskResponse response = TaskResponse.from(task, Map.of(7L, 1));

        assertThat(response.getStatus()).isEqualTo("QUEUED");
        assertThat(response.getQueuedAt()).isEqualTo(LocalDateTime.of(2026, 10, 10, 1, 2, 3));
        assertThat(response.getQueuePosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("供给中的 QUEUED、其它状态和位次表里没有的任务不带位次")
    void hidesPositionUnlessWaiting() {
        Task provisioning = task("QUEUED");
        assertThat(TaskResponse.from(provisioning, Map.of(7L, 1)).getQueuePosition()).isNull();

        Task running = task("RUNNING");
        running.setQueuedAt(LocalDateTime.of(2026, 10, 10, 1, 0));
        assertThat(TaskResponse.from(running, Map.of(7L, 3)).getQueuePosition()).isNull();

        Task waiting = task("QUEUED");
        waiting.setQueuedAt(LocalDateTime.of(2026, 10, 10, 1, 0));
        assertThat(TaskResponse.from(waiting, Map.of()).getQueuePosition()).isNull();
        assertThat(TaskResponse.from(waiting, null).getQueuePosition()).isNull();
        assertThat(TaskResponse.from(waiting).getQueuedAt()).isEqualTo(waiting.getQueuedAt());
        assertThat(TaskResponse.from(waiting).getQueuePosition()).isNull();
    }

    private static Task task(String status) {
        Task task = new Task();
        task.setId(7L);
        task.setStatus(status);
        task.setTaskCode("Q-7");
        return task;
    }
}
