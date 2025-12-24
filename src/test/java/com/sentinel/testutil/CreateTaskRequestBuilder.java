package com.sentinel.testutil;

import com.sentinel.dto.CreateTaskRequest;

/**
 * CreateTaskRequest 测试数据构建器
 */
public class CreateTaskRequestBuilder {

    private String taskCode = "TEST-" + System.currentTimeMillis();
    private String serviceId = "test-service";
    private String testType = "UNIT_TEST";
    private String dependencies = "[\"mysql:8.0\"]";
    private Integer priority = 5;

    public static CreateTaskRequestBuilder aCreateTaskRequest() {
        return new CreateTaskRequestBuilder();
    }

    public CreateTaskRequestBuilder withTaskCode(String taskCode) {
        this.taskCode = taskCode;
        return this;
    }

    public CreateTaskRequestBuilder withServiceId(String serviceId) {
        this.serviceId = serviceId;
        return this;
    }

    public CreateTaskRequestBuilder withTestType(String testType) {
        this.testType = testType;
        return this;
    }

    public CreateTaskRequestBuilder withDependencies(String dependencies) {
        this.dependencies = dependencies;
        return this;
    }

    public CreateTaskRequestBuilder withPriority(Integer priority) {
        this.priority = priority;
        return this;
    }

    public CreateTaskRequestBuilder unitTest() {
        this.testType = "UNIT_TEST";
        return this;
    }

    public CreateTaskRequestBuilder integrationTest() {
        this.testType = "INTEGRATION_TEST";
        return this;
    }

    public CreateTaskRequestBuilder e2eTest() {
        this.testType = "E2E_TEST";
        return this;
    }

    public CreateTaskRequest build() {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskCode(taskCode);
        request.setServiceId(serviceId);
        request.setTestType(testType);
        request.setDependencies(dependencies);
        request.setPriority(priority);
        return request;
    }
}
