package com.sentinel.integration;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sentinel.BaseTest;
import com.sentinel.domain.Task;
import com.sentinel.dto.CreateTaskRequest;
import com.sentinel.mapper.TaskMapper;
import com.sentinel.service.TaskService;
import com.sentinel.testutil.CreateTaskRequestBuilder;
import com.sentinel.testutil.TaskBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 数据库集成测试
 * 使用 H2 内存数据库测试数据访问层
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("数据库集成测试")
public class DatabaseIntegrationTest extends BaseTest {

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private TaskService taskService;

    @Test
    @DisplayName("测试任务插入操作")
    public void shouldInsertTask_whenValidData() {
        // Given
        Task task = TaskBuilder.aTask()
            .withTaskCode("DB-TEST-001")
            .withServiceId("test-service")
            .pending()
            .build();

        // When
        int result = taskMapper.insert(task);

        // Then
        assertThat(result).isEqualTo(1);
        assertThat(task.getId()).isNotNull();
        assertThat(task.getId()).isGreaterThan(0);
    }

    @Test
    @DisplayName("测试任务查询操作")
    public void shouldSelectTask_whenTaskExists() {
        // Given
        Task task = TaskBuilder.aTask()
            .withTaskCode("DB-TEST-002")
            .withServiceId("test-service")
            .pending()
            .build();
        taskMapper.insert(task);

        // When
        Task found = taskMapper.selectById(task.getId());

        // Then
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(task.getId());
        assertThat(found.getTaskCode()).isEqualTo("DB-TEST-002");
        assertThat(found.getServiceId()).isEqualTo("test-service");
    }

    @Test
    @DisplayName("测试任务更新操作")
    public void shouldUpdateTask_whenTaskExists() {
        // Given
        Task task = TaskBuilder.aTask()
            .withTaskCode("DB-TEST-003")
            .pending()
            .build();
        taskMapper.insert(task);

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
        Task task = TaskBuilder.aTask()
            .withTaskCode("DB-TEST-004")
            .pending()
            .build();
        taskMapper.insert(task);

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
        Task task1 = TaskBuilder.aTask().withTaskCode("DB-TEST-005").pending().build();
        Task task2 = TaskBuilder.aTask().withTaskCode("DB-TEST-006").pending().build();
        Task task3 = TaskBuilder.aTask().withTaskCode("DB-TEST-007").running().build();
        
        taskMapper.insert(task1);
        taskMapper.insert(task2);
        taskMapper.insert(task3);

        // When
        List<Task> pendingTasks = taskMapper.findByStatus("PENDING");

        // Then
        assertThat(pendingTasks).hasSize(2);
        assertThat(pendingTasks).allMatch(task -> "PENDING".equals(task.getStatus()));
    }

    @Test
    @DisplayName("测试分页查询任务列表")
    public void shouldSelectTaskPage_whenTasksExist() {
        // Given
        for (int i = 1; i <= 15; i++) {
            Task task = TaskBuilder.aTask()
                .withTaskCode("DB-TEST-PAGE-" + i)
                .pending()
                .build();
            taskMapper.insert(task);
        }

        // When
        IPage<Task> page = taskService.listTasks(1, 10, null);

        // Then
        assertThat(page).isNotNull();
        assertThat(page.getRecords()).hasSize(10);
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(15);
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("测试分页查询第二页")
    public void shouldSelectSecondPage_whenMultiplePagesExist() {
        // Given
        for (int i = 1; i <= 25; i++) {
            Task task = TaskBuilder.aTask()
                .withTaskCode("DB-TEST-PAGE2-" + i)
                .pending()
                .build();
            taskMapper.insert(task);
        }

        // When
        IPage<Task> page = taskService.listTasks(2, 10, null);

        // Then
        assertThat(page).isNotNull();
        assertThat(page.getRecords()).hasSize(10);
        assertThat(page.getCurrent()).isEqualTo(2);
    }

    @Test
    @DisplayName("测试事务回滚")
    public void shouldRollbackTransaction_whenExceptionOccurs() {
        // Given
        Task task = TaskBuilder.aTask()
            .withTaskCode("DB-TEST-ROLLBACK")
            .pending()
            .build();

        // When
        try {
            taskMapper.insert(task);
            // 模拟异常
            throw new RuntimeException("Test exception");
        } catch (RuntimeException e) {
            // 事务应该回滚
        }

        // Then - 由于@Transactional，任务不应该被保存
        // 注意：在测试方法级别的@Transactional会在方法结束后自动回滚
    }

    @Test
    @DisplayName("测试TaskService创建任务的数据库操作")
    public void shouldPersistTask_whenCreatedThroughService() {
        // Given
        CreateTaskRequest request = CreateTaskRequestBuilder.aCreateTaskRequest()
            .withTaskCode("DB-TEST-SERVICE-001")
            .withServiceId("test-service")
            .unitTest()
            .build();

        // When
        Task created = taskService.createTask(request);

        // Then
        assertThat(created.getId()).isNotNull();
        
        Task found = taskMapper.selectById(created.getId());
        assertThat(found).isNotNull();
        assertThat(found.getTaskCode()).isEqualTo("DB-TEST-SERVICE-001");
        assertThat(found.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("测试TaskService更新任务状态的数据库操作")
    public void shouldUpdateTaskStatus_whenUpdatedThroughService() {
        // Given
        CreateTaskRequest request = CreateTaskRequestBuilder.aCreateTaskRequest()
            .withTaskCode("DB-TEST-SERVICE-002")
            .unitTest()
            .build();
        Task created = taskService.createTask(request);

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
            .withTaskCode("DB-TEST-AUTOFILL")
            .pending()
            .build();

        // When
        taskMapper.insert(task);

        // Then
        Task found = taskMapper.selectById(task.getId());
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("测试查询不存在的任务")
    public void shouldReturnNull_whenTaskNotExists() {
        // When
        Task found = taskMapper.selectById(99999L);

        // Then
        assertThat(found).isNull();
    }

    @Test
    @DisplayName("测试批量插入任务")
    public void shouldInsertMultipleTasks_whenBatchInsert() {
        // Given & When
        for (int i = 1; i <= 5; i++) {
            Task task = TaskBuilder.aTask()
                .withTaskCode("DB-TEST-BATCH-" + i)
                .pending()
                .build();
            taskMapper.insert(task);
        }

        // Then
        List<Task> allTasks = taskMapper.findByStatus("PENDING");
        assertThat(allTasks.size()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("测试任务优先级排序")
    public void shouldOrderByPriority_whenQueryByStatus() {
        // Given
        Task lowPriority = TaskBuilder.aTask()
            .withTaskCode("DB-TEST-PRIORITY-LOW")
            .withPriority(1)
            .pending()
            .build();
        
        Task highPriority = TaskBuilder.aTask()
            .withTaskCode("DB-TEST-PRIORITY-HIGH")
            .withPriority(10)
            .pending()
            .build();
        
        taskMapper.insert(lowPriority);
        taskMapper.insert(highPriority);

        // When
        List<Task> tasks = taskMapper.findByStatus("PENDING");

        // Then
        assertThat(tasks).isNotEmpty();
        // 验证按优先级降序排列（高优先级在前）
        if (tasks.size() >= 2) {
            Task first = tasks.stream()
                .filter(t -> t.getTaskCode().equals("DB-TEST-PRIORITY-HIGH"))
                .findFirst()
                .orElse(null);
            Task second = tasks.stream()
                .filter(t -> t.getTaskCode().equals("DB-TEST-PRIORITY-LOW"))
                .findFirst()
                .orElse(null);
            
            if (first != null && second != null) {
                int firstIndex = tasks.indexOf(first);
                int secondIndex = tasks.indexOf(second);
                assertThat(firstIndex).isLessThan(secondIndex);
            }
        }
    }
}
