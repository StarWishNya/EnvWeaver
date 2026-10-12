package com.sentinel.service;

import com.sentinel.domain.Task;

import java.util.Comparator;

/**
 * 排队顺序：priority 降序，queuedAt 升序，id 升序。
 * 与 {@code t_task} 的出队查询一致。已在排队的任务，优先级大于等于新任务时先出队。
 */
public final class QueueOrder {

    public static final Comparator<Task> COMPARATOR = Comparator
            .comparingInt(QueueOrder::priority)
            .reversed()
            .thenComparing(Task::getQueuedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingLong(QueueOrder::id);

    private QueueOrder() {
    }

    /**
     * 已经在排队的任务是否应该排在候选任务前面。同优先级时，先排队的优先。
     */
    public static boolean outranks(Task waiting, Task candidate) {
        if (waiting == null || candidate == null) {
            return false;
        }
        return priority(waiting) >= priority(candidate);
    }

    private static int priority(Task task) {
        if (task == null || task.getPriority() == null) {
            return 0;
        }
        return task.getPriority();
    }

    private static long id(Task task) {
        if (task == null || task.getId() == null) {
            return Long.MAX_VALUE;
        }
        return task.getId();
    }
}
