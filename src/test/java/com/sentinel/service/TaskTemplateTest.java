package com.sentinel.service;

import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.domain.Task;
import com.sentinel.dto.CreateTaskRequest;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.TaskMapper;
import com.sentinel.service.provision.DependencyCatalog;
import com.sentinel.service.provision.MiddlewareCatalog;
import com.sentinel.service.provision.ResolveFailure;
import com.sentinel.service.provision.TemplateCatalog;
import com.sentinel.testutil.CreateTaskRequestBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 创建任务时展开模板，并在写入前拒绝未知依赖。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("任务模板")
class TaskTemplateTest {

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private DockerService dockerService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        MiddlewareCatalog middleware = MiddlewareCatalog.load();
        taskService = new TaskService(
                taskMapper,
                dockerService,
                eventPublisher,
                TemplateCatalog.load(middleware),
                new DependencyCatalog(middleware, new ProvisioningProperties()));
    }

    @Test
    @DisplayName("只给模板时写入模板依赖")
    void templateAloneExpandsDependencies() {
        when(taskMapper.insert(any(Task.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Task.class).setId(4L);
            return 1;
        });
        CreateTaskRequest request = CreateTaskRequestBuilder.aCreateTaskRequest()
                .withDependencies(List.of())
                .build();
        request.setTemplate("data-stack");

        Task created = taskService.createTask(request);

        ArgumentCaptor<Task> inserted = ArgumentCaptor.forClass(Task.class);
        verify(taskMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getDependencies()).isEqualTo("[\"postgres:16\",\"redis:7\"]");
        assertThat(created.getId()).isEqualTo(4L);
    }

    @Test
    @DisplayName("模板与显式依赖合并，同一种类以显式为准")
    void explicitDependencyOverridesTemplateKind() {
        when(taskMapper.insert(any(Task.class))).thenReturn(1);
        CreateTaskRequest request = CreateTaskRequestBuilder.aCreateTaskRequest()
                .withDependencies(List.of("redis:6.2", "nginx"))
                .build();
        request.setTemplate("data-stack");

        taskService.createTask(request);

        ArgumentCaptor<Task> inserted = ArgumentCaptor.forClass(Task.class);
        verify(taskMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getDependencies())
                .isEqualTo("[\"postgres:16\",\"redis:6.2\",\"nginx\"]");
    }

    @Test
    @DisplayName("未知模板不写库，并带上候选名称")
    void unknownTemplateDoesNotInsert() {
        CreateTaskRequest request = CreateTaskRequestBuilder.aCreateTaskRequest()
                .withDependencies(null)
                .build();
        request.setTemplate("nope");

        BusinessException failure = catchThrowableOfType(() -> taskService.createTask(request), BusinessException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.getCode());
        assertThat(failure.getMessage()).contains("未知模板: nope");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) failure.getData();
        assertThat(data.get("candidates")).isEqualTo(List.of("web-stack", "data-stack", "mq-stack"));
        verify(taskMapper, never()).insert(any(Task.class));
    }

    @Test
    @DisplayName("未知版本不写库，并带上可选版本")
    void unknownVersionDoesNotInsert() {
        CreateTaskRequest request = CreateTaskRequestBuilder.aCreateTaskRequest()
                .withDependencies(List.of("mysql:9.9"))
                .build();

        BusinessException failure = catchThrowableOfType(() -> taskService.createTask(request), BusinessException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.getCode());
        assertThat(failure.getMessage()).contains("mysql:9.9").contains("5.7").contains("8.0");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) failure.getData();
        assertThat(data.get("code")).isEqualTo(ResolveFailure.UNKNOWN_VERSION);
        assertThat(data.get("candidates")).isEqualTo(List.of("5.7", "8.0"));
        verify(taskMapper, never()).insert(any(Task.class));
    }
}
