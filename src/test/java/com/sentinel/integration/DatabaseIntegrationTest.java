package com.sentinel.integration;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sentinel.BaseTest;
import com.sentinel.domain.Task;
import com.sentinel.dto.CreateTaskRequest;
import com.sentinel.mapper.TaskMapper;
import com.sentinel.service.TaskService;
import com.sentinel.testutil.CreateTaskRequestBuilder;
import com.sentinel.testutil.TaskBuilder;
import com.sentinel.testutil.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 数据库集成测试
 * 连接测试 profile 的 MySQL。每个用例在事务内执行并在结束后回滚；
 * 事务结束后再按主键删除本次插入的行，避免回滚未生效时污染 t_task。
 * 插入使用唯一任务编码，断言只针对本用例写入的行。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Rollback
@DisplayName("数据库集成测试")
public class DatabaseIntegrationTest extends BaseTest {

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private TaskService taskService;

    /** 本用例实例的任务编码后缀，t_task.task_code 唯一 */
    private final String runToken = TestUtils.uniqueToken();

    /** 本用例插入的主键，供回滚失败时清理 */
    private final List<Long> insertedTaskIds = new ArrayList<>();

    @AfterEach
    public void deleteInsertedTasksBeforeRollback() {
        deleteTrackedTasks();
    }

    @AfterTransaction
    public void deleteInsertedTasksAfterRollback() {
        deleteTrackedTasks();
    }

    @Test
    @DisplayName("测试任务插入操作")
    public void shouldInsertTask_whenValidData() {
        // Given
        Task task = TaskBuilder.aTask()
            .withTaskCode(taskCode("DB-TEST-001"))
            .withServiceId("test-service")
            .pending()
            .build();

        // When
        int result = taskMapper.insert(task);
        remember(task);

        // Then
        assertThat(result).isEqualTo(1);
        assertThat(task.getId()).isNotNull();
        assertThat(task.getId()).isGreaterThan(0);
    }

    @Test
    @DisplayName("测试任务查询操作")
    public void shouldSelectTask_whenTaskExists() {
        // Given
        String code = taskCode("DB-TEST-002");
        Task task = insertTask(TaskBuilder.aTask()
            .withTaskCode(code)
            .withServiceId("test-service")
            .pending()
            .build());

        // When
        Task found = taskMapper.selectById(task.getId());

        // Then
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(task.getId());
        assertThat(found.getTaskCode()).isEqualTo(code);
        assertThat(found.getServiceId()).isEqualTo("test-service");
    }

    @Test
    @DisplayName("测试任务更新操作")
    public void shouldUpdateTask_whenTaskExists() {
        // Given
        Task task = insertTask(TaskBuilder.aTask()
            .withTaskCode(taskCode("DB-TEST-003"))
            .pending()
            .build());

        // When
        task.setStatus("RUNNING");
        int result = taskMapper.updateById(task);

        // Then
        assertThat(result).isEqualTo(1);
        Task updated = taskMapper.selectById(task.getId());
        assertThat(updated.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("测试任务删除操作")
    public void shouldDeleteTask_whenTaskExists() {
        // Given
        Task task = insertTask(TaskBuilder.aTask()
            .withTaskCode(taskCode("DB-TEST-004"))
            .pending()
            .build());

        // When
        int result = taskMapper.deleteById(task.getId());

        // Then
        assertThat(result).isEqualTo(1);
        Task deleted = taskMapper.selectById(task.getId());
        assertThat(deleted).isNull();
    }

    @Test
    @DisplayName("测试根据状态查询任务列表")
    public void shouldFindTasksByStatus_whenTasksExist() {
        // Given
        String pendingCode1 = taskCode("DB-TEST-005");
        String pendingCode2 = taskCode("DB-TEST-006");
        String runningCode = taskCode("DB-TEST-007");
        insertTask(TaskBuilder.aTask().withTaskCode(pendingCode1).pending().build());
        insertTask(TaskBuilder.aTask().withTaskCode(pendingCode2).pending().build());
        Task runningTask = insertTask(TaskBuilder.aTask().withTaskCode(runningCode).running().build());

        // When
        List<Task> pendingTasks = taskMapper.findByStatus("PENDING");

        // Then：只断言本用例插入的行，不依赖库中已有的 PENDING 数量
        List<Task> insertedPending = pendingTasks.stream()
            .filter(task -> pendingCode1.equals(task.getTaskCode()) || pendingCode2.equals(task.getTaskCode()))
            .toList();
        assertThat(insertedPending).hasSize(2);
        assertThat(insertedPending).allMatch(task -> "PENDING".equals(task.getStatus()));
        assertThat(pendingTasks).extracting(Task::getTaskCode).doesNotContain(runningCode);

        Task storedRunning = taskMapper.selectById(runningTask.getId());
        assertThat(storedRunning).isNotNull();
        assertThat(storedRunning.getTaskCode()).isEqualTo(runningCode);
        assertThat(storedRunning.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("测试分页查询任务列表")
    public void shouldSelectTaskPage_whenTasksExist() {
        // Given
        long totalBefore = taskService.listTasks(1, 1, null).getTotal();
        List<String> codes = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            String code = taskCode("DB-TEST-PAGE-" + i);
            codes.add(code);
            insertTask(TaskBuilder.aTask().withTaskCode(code).pending().build());
        }

        // When
        IPage<Task> page = taskService.listTasks(1, 10, null);

        // Then
        assertThat(page).isNotNull();
        assertThat(page.getRecords()).hasSize(10);
        assertThat(page.getTotal()).isEqualTo(totalBefore + codes.size());
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("测试分页查询第二页")
    public void shouldSelectSecondPage_whenMultiplePagesExist() {
        // Given
        long totalBefore = taskService.listTasks(1, 1, null).getTotal();
        int inserted = 25;
        for (int i = 1; i <= inserted; i++) {
            insertTask(TaskBuilder.aTask()
                .withTaskCode(taskCode("DB-TEST-PAGE2-" + i))
                .pending()
                .build());
        }

        // When
        IPage<Task> page = taskService.listTasks(2, 10, null);

        // Then
        assertThat(page).isNotNull();
        assertThat(page.getTotal()).isEqualTo(totalBefore + inserted);
        assertThat(page.getRecords()).hasSize(10);
        assertThat(page.getCurrent()).isEqualTo(2);
    }

    @Test
    @DisplayName("测试事务回滚")
    public void shouldRollbackTransaction_whenExceptionOccurs() {
        // Given
        assertThat(TestTransaction.isActive()).isTrue();
        Task task = insertTask(TaskBuilder.aTask()
            .withTaskCode(taskCode("DB-TEST-ROLLBACK"))
            .pending()
            .build());
        Long taskId = task.getId();
        assertThat(taskMapper.selectById(taskId)).isNotNull();

        // When：用例内部异常被吞掉，仍应回滚未提交的插入
        try {
            throw new RuntimeException("Test exception");
        } catch (RuntimeException ex) {
            assertThat(ex).hasMessage("Test exception");
        }

        TestTransaction.flagForRollback();
        TestTransaction.end();
        TestTransaction.start();

        // Then
        assertThat(taskMapper.selectById(taskId)).isNull();
    }

    @Test
    @DisplayName("测试TaskService创建任务的数据库操作")
    public void shouldPersistTask_whenCreatedThroughService() {
        // Given
        CreateTaskRequest request = CreateTaskRequestBuilder.aCreateTaskRequest()
            .withTaskCode(taskCode("DB-TEST-SERVICE-001"))
            .withServiceId("test-service")
            .unitTest()
            .build();

        // When
        Task created = taskService.createTask(request);
        remember(created);

        // Then
        assertThat(created.getId()).isNotNull();

        Task found = taskMapper.selectById(created.getId());
        assertThat(found).isNotNull();
        assertThat(found.getTaskCode()).isEqualTo(request.getTaskCode());
        assertThat(found.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("测试TaskService更新任务状态的数据库操作")
    public void shouldUpdateTaskStatus_whenUpdatedThroughService() {
        // Given
        CreateTaskRequest request = CreateTaskRequestBuilder.aCreateTaskRequest()
            .withTaskCode(taskCode("DB-TEST-SERVICE-002"))
            .unitTest()
            .build();
        Task created = taskService.createTask(request);
        remember(created);

        // When
        taskService.updateTaskStatus(created.getId(), "RUNNING");

        // Then
        Task updated = taskMapper.selectById(created.getId());
        assertThat(updated.getStatus()).isEqualTo("RUNNING");
        assertThat(updated.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("测试MyBatis-Plus自动填充功能")
    public void shouldAutoFillTimestamps_whenInsertTask() {
        // Given
        Task task = TaskBuilder.aTask()
            .withTaskCode(taskCode("DB-TEST-AUTOFILL"))
            .pending()
            .build();
        task.setCreatedAt(null);
        task.setUpdatedAt(null);

        // When
        insertTask(task);

        // Then
        Task found = taskMapper.selectById(task.getId());
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("测试查询不存在的任务")
    public void shouldReturnNull_whenTaskNotExists() {
        // When：自增主键不会是负数，避免与已有行碰撞
        Task found = taskMapper.selectById(-1L);

        // Then
        assertThat(found).isNull();
    }

    @Test
    @DisplayName("测试批量插入任务")
    public void shouldInsertMultipleTasks_whenBatchInsert() {
        // Given & When
        List<String> codes = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String code = taskCode("DB-TEST-BATCH-" + i);
            codes.add(code);
            insertTask(TaskBuilder.aTask().withTaskCode(code).pending().build());
        }

        // Then
        List<Task> pendingTasks = taskMapper.findByStatus("PENDING");
        List<Task> inserted = pendingTasks.stream()
            .filter(task -> codes.contains(task.getTaskCode()))
            .toList();
        assertThat(inserted).hasSize(codes.size());
        assertThat(inserted).allMatch(task -> "PENDING".equals(task.getStatus()));
    }

    @Test
    @DisplayName("测试任务优先级排序")
    public void shouldOrderByPriority_whenQueryByStatus() {
        // Given
        String lowCode = taskCode("DB-TEST-PRIORITY-LOW");
        String highCode = taskCode("DB-TEST-PRIORITY-HIGH");
        insertTask(TaskBuilder.aTask()
            .withTaskCode(lowCode)
            .withPriority(1)
            .pending()
            .build());
        insertTask(TaskBuilder.aTask()
            .withTaskCode(highCode)
            .withPriority(10)
            .pending()
            .build());

        // When
        List<Task> tasks = taskMapper.findByStatus("PENDING");

        // Then：高优先级排在低优先级之前，不受库中其他 PENDING 行影响
        int highIndex = indexOfTaskCode(tasks, highCode);
        int lowIndex = indexOfTaskCode(tasks, lowCode);
        assertThat(highIndex).isGreaterThanOrEqualTo(0);
        assertThat(lowIndex).isGreaterThanOrEqualTo(0);
        assertThat(highIndex).isLessThan(lowIndex);
    }

    private String taskCode(String base) {
        String code = base + "-" + runToken;
        if (code.length() > 64) {
            throw new IllegalArgumentException("task_code exceeds 64 chars: " + code);
        }
        return code;
    }

    private Task insertTask(Task task) {
        int result = taskMapper.insert(task);
        assertThat(result).isEqualTo(1);
        remember(task);
        return task;
    }

    private void remember(Task task) {
        assertThat(task.getId()).isNotNull();
        insertedTaskIds.add(task.getId());
    }

    private void deleteTrackedTasks() {
        for (Long taskId : new ArrayList<>(insertedTaskIds)) {
            taskMapper.deleteById(taskId);
        }
    }

    private int indexOfTaskCode(List<Task> tasks, String code) {
        for (int i = 0; i < tasks.size(); i++) {
            if (code.equals(tasks.get(i).getTaskCode())) {
                return i;
            }
        }
        return -1;
    }
}
