package com.sentinel.testutil;

import com.sentinel.domain.Task;

import java.time.LocalDateTime;

/**
 * Task 测试数据构建器
 * 使用 Builder 模式创建测试数据
 */
public class TaskBuilder {

    private Long id;
    private String taskCode = "TEST-" + System.currentTimeMillis();
    private String serviceId = "test-service";
    private String testType = "UNIT_TEST";
    private String dependencies = "[\"mysql:8.0\"]";
    private String status = "PENDING";
    private Integer priority = 5;
    private Long environmentId = 1L;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public static TaskBuilder aTask() {
        return new TaskBuilder();
    }

    public TaskBuilder withId(Long id) {
        this.id = id;
        return this;
    }

    public TaskBuilder withTaskCode(String taskCode) {
        this.taskCode = taskCode;
        return this;
    }

    public TaskBuilder withServiceId(String serviceId) {
        this.serviceId = serviceId;
        return this;
    }

    public TaskBuilder withTestType(String testType) {
        this.testType = testType;
        return this;
    }

    public TaskBuilder withDependencies(String dependencies) {
        this.dependencies = dependencies;
        return this;
    }

    public TaskBuilder withStatus(String status) {
        this.status = status;
        return this;
    }

    public TaskBuilder withPriority(Integer priority) {
        this.priority = priority;
        return this;
    }

    public TaskBuilder withEnvironmentId(Long environmentId) {
        this.environmentId = environmentId;
        return this;
    }

    public TaskBuilder withErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        return this;
    }

    public TaskBuilder withStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
        return this;
    }

    public TaskBuilder withCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
        return this;
    }

    public TaskBuilder withCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    public TaskBuilder withUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
        return this;
    }

    public TaskBuilder pending() {
        this.status = "PENDING";
        return this;
    }

    public TaskBuilder running() {
        this.status = "RUNNING";
        this.startedAt = LocalDateTime.now();
        return this;
    }

    public TaskBuilder success() {
        this.status = "SUCCESS";
        this.startedAt = LocalDateTime.now().minusMinutes(5);
        this.completedAt = LocalDateTime.now();
        return this;
    }

    public TaskBuilder failed() {
        this.status = "FAILED";
        this.startedAt = LocalDateTime.now().minusMinutes(5);
        this.completedAt = LocalDateTime.now();
        this.errorMessage = "Test failed";
        return this;
    }

    public TaskBuilder cancelled() {
        this.status = "CANCELLED";
        this.completedAt = LocalDateTime.now();
        return this;
    }

    public Task build() {
        Task task = new Task();
        task.setId(id);
        task.setTaskCode(taskCode);
        task.setServiceId(serviceId);
        task.setTestType(testType);
        task.setDependencies(dependencies);
        task.setStatus(status);
        task.setPriority(priority);
        task.setEnvironmentId(environmentId);
        task.setErrorMessage(errorMessage);
        task.setStartedAt(startedAt);
        task.setCompletedAt(completedAt);
        task.setCreatedAt(createdAt);
        task.setUpdatedAt(updatedAt);
        return task;
    }
}
