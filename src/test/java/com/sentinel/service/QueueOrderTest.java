package com.sentinel.service;

import com.sentinel.domain.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出队顺序：优先级降序，排队时间升序，id 升序。
 */
@DisplayName("排队顺序")
class QueueOrderTest {

    private static final LocalDateTime EARLY = LocalDateTime.of(2026, 10, 10, 1, 0);
    private static final LocalDateTime LATE = LocalDateTime.of(2026, 10, 10, 2, 0);

    @Test
    @DisplayName("高优先级在前；同优先级更早排队的在前；再按 id")
    void ordersByPriorityThenQueuedAtThenId() {
        Task low = task(3L, 1, EARLY);
        Task lateHigh = task(9L, 8, LATE);
        Task earlyHighLaterId = task(5L, 8, EARLY);
        Task earlyHighEarlierId = task(2L, 8, EARLY);
        Task missingTime = task(1L, 8, null);
        Task missingPriority = task(4L, null, EARLY);

        List<Task> tasks = new ArrayList<>(List.of(
                low, lateHigh, earlyHighLaterId, missingTime, missingPriority, earlyHighEarlierId));
        tasks.sort(QueueOrder.COMPARATOR);

        assertThat(tasks).extracting(Task::getId).containsExactly(2L, 5L, 9L, 1L, 3L, 4L);
    }

    @Test
    @DisplayName("已排队任务优先级大于等于候选时排在前面")
    void waitingOutranksEqualOrLowerPriority() {
        Task waiting = task(1L, 5, EARLY);
        Task same = task(2L, 5, LATE);
        Task lower = task(3L, 4, EARLY);
        Task higher = task(4L, 6, EARLY);

        assertThat(QueueOrder.outranks(waiting, same)).isTrue();
        assertThat(QueueOrder.outranks(waiting, lower)).isTrue();
        assertThat(QueueOrder.outranks(waiting, higher)).isFalse();
        assertThat(QueueOrder.outranks(null, same)).isFalse();
        assertThat(QueueOrder.outranks(waiting, null)).isFalse();
        assertThat(QueueOrder.outranks(task(1L, null, EARLY), task(2L, 0, LATE))).isTrue();
    }

    private static Task task(Long id, Integer priority, LocalDateTime queuedAt) {
        Task task = new Task();
        task.setId(id);
        task.setPriority(priority);
        task.setQueuedAt(queuedAt);
        task.setStatus("QUEUED");
        return task;
    }
}
