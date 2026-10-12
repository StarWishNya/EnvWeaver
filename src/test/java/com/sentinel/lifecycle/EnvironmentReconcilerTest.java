package com.sentinel.lifecycle;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.github.dockerjava.api.exception.DockerException;
import com.sentinel.config.properties.LifecycleProperties;
import com.sentinel.domain.Container;
import com.sentinel.domain.Environment;
import com.sentinel.event.EnvironmentReleasedEvent;
import com.sentinel.mapper.ContainerMapper;
import com.sentinel.mapper.EnvironmentMapper;
import com.sentinel.service.TaskService;
import com.sentinel.service.provision.ContainerLauncher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 容器核对。不启动 Docker，也不访问数据库。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("环境容器核对")
class EnvironmentReconcilerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-12T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
    private static final String REASON = EnvironmentReconciler.MISSING_REASON;

    @Mock
    private EnvironmentMapper environmentMapper;

    @Mock
    private ContainerMapper containerMapper;

    @Mock
    private ContainerLauncher containerLauncher;

    @Mock
    private TaskService taskService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private LifecycleProperties properties;
    private EnvironmentReconciler reconciler;

    @BeforeAll
    static void initLambdaCache() {
        MybatisLambdaCaches.install(Environment.class);
    }

    @BeforeEach
    void setUp() {
        properties = new LifecycleProperties();
        reconciler = new EnvironmentReconciler(properties, environmentMapper, containerMapper,
                containerLauncher, taskService, eventPublisher, CLOCK);
    }

    @Test
    @DisplayName("容器全部缺失时把环境标为 ERROR、运行中的任务标为 FAILED，并发布释放事件")
    void shouldFailRunningTaskWhenEveryContainerIsMissing() {
        Environment environment = busyEnvironment(8L, 3L, "services:\n  redis:\n    image: redis:7\n");
        Container first = container(21L, 8L, "cid-a");
        Container second = container(22L, 8L, "cid-b");
        when(environmentMapper.selectByStatuses(eq(EnvironmentReconciler.RECONCILE_STATUSES), anyInt()))
                .thenReturn(List.of(environment));
        when(containerMapper.findByEnvironmentId(8L)).thenReturn(List.of(first, second));
        when(containerLauncher.exists("cid-a")).thenReturn(false);
        when(containerLauncher.exists("cid-b")).thenReturn(false);
        when(environmentMapper.update(isNull(), any())).thenReturn(1);

        reconciler.reconcile();

        ArgumentCaptor<Wrapper<Environment>> envUpdate = ArgumentCaptor.forClass(Wrapper.class);
        ArgumentCaptor<Container> stopped = ArgumentCaptor.forClass(Container.class);
        var order = inOrder(environmentMapper, containerMapper, taskService, eventPublisher);
        order.verify(environmentMapper).update(isNull(), envUpdate.capture());
        order.verify(containerMapper, times(2)).updateById(stopped.capture());
        order.verify(taskService).failIfRunning(3L, REASON);
        order.verify(eventPublisher).publishEvent(new EnvironmentReleasedEvent(8L));
        verify(taskService, never()).lookupTask(any());
        verify(taskService, never()).setTaskError(any(), any());

        assertMissingEnvironmentUpdate(envUpdate.getValue(), 8L, "BUSY",
                "# " + REASON + "\nservices:\n  redis:\n    image: redis:7\n");
        assertThat(stopped.getAllValues()).extracting(Container::getId).containsExactly(21L, 22L);
        assertThat(stopped.getAllValues()).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo("STOPPED");
            assertThat(row.getStoppedAt()).isEqualTo(NOW);
            assertThat(row.getLastLog()).isEqualTo(REASON);
        });
    }

    @Test
    @DisplayName("无依赖空环境没有容器记录时不改状态")
    void shouldSkipIntentionalEmptyEnvironment() {
        Environment environment = busyEnvironment(4L, 9L, "services: {}\n");
        environment.setPortMappings("{}");
        when(environmentMapper.selectByStatuses(any(), anyInt())).thenReturn(List.of(environment));
        when(containerMapper.findByEnvironmentId(4L)).thenReturn(List.of());

        reconciler.reconcile();

        verify(containerLauncher, never()).exists(any());
        verify(environmentMapper, never()).update(any(), any());
        verify(taskService, never()).failIfRunning(any(), any());
        verify(taskService, never()).setTaskError(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("本该有容器但没有任何容器记录时标为 ERROR 并发布释放事件")
    void shouldMarkEnvironmentErrorWhenItHasNoContainerRows() {
        Environment environment = busyEnvironment(4L, 9L, "services:\n  redis:\n    image: redis:7\n");
        when(environmentMapper.selectByStatuses(any(), anyInt())).thenReturn(List.of(environment));
        when(containerMapper.findByEnvironmentId(4L)).thenReturn(List.of());
        when(environmentMapper.update(isNull(), any())).thenReturn(1);

        reconciler.reconcile();

        ArgumentCaptor<Wrapper<Environment>> envUpdate = ArgumentCaptor.forClass(Wrapper.class);
        verify(environmentMapper).update(isNull(), envUpdate.capture());
        assertMissingEnvironmentUpdate(envUpdate.getValue(), 4L, "BUSY",
                "# " + REASON + "\nservices:\n  redis:\n    image: redis:7\n");
        verify(containerLauncher, never()).exists(any());
        verify(containerMapper, never()).updateById(any(Container.class));
        verify(taskService).failIfRunning(9L, REASON);
        verify(taskService, never()).lookupTask(any());
        verify(taskService, never()).setTaskError(any(), any());
        verify(eventPublisher).publishEvent(new EnvironmentReleasedEvent(4L));
    }

    @Test
    @DisplayName("只有部分容器缺失时停止缺失容器，环境标为 ERROR，并发布释放事件")
    void shouldStopOnlyMissingContainersWhenSomeRemain() {
        Environment environment = busyEnvironment(6L, 2L, "services: {}\n");
        environment.setStatus("RETAINED");
        Container gone = container(31L, 6L, "gone");
        Container alive = container(32L, 6L, "alive");
        alive.setStatus("RUNNING");
        when(environmentMapper.selectByStatuses(any(), anyInt())).thenReturn(List.of(environment));
        when(containerMapper.findByEnvironmentId(6L)).thenReturn(List.of(gone, alive));
        when(containerLauncher.exists("gone")).thenReturn(false);
        when(containerLauncher.exists("alive")).thenReturn(true);
        when(environmentMapper.update(isNull(), any())).thenReturn(1);

        reconciler.reconcile();

        ArgumentCaptor<Wrapper<Environment>> envUpdate = ArgumentCaptor.forClass(Wrapper.class);
        ArgumentCaptor<Container> stopped = ArgumentCaptor.forClass(Container.class);
        verify(environmentMapper).update(isNull(), envUpdate.capture());
        verify(containerMapper).updateById(stopped.capture());
        assertMissingEnvironmentUpdate(envUpdate.getValue(), 6L, "RETAINED",
                "# " + REASON + "\nservices: {}\n");
        assertThat(stopped.getValue().getId()).isEqualTo(31L);
        assertThat(stopped.getValue().getStatus()).isEqualTo("STOPPED");
        assertThat(alive.getStatus()).isEqualTo("RUNNING");
        verify(taskService).failIfRunning(2L, REASON);
        verify(taskService, never()).lookupTask(any());
        verify(taskService, never()).setTaskError(any(), any());
        verify(eventPublisher).publishEvent(new EnvironmentReleasedEvent(6L));
    }

    @Test
    @DisplayName("容器都在时不改环境、不改任务、不发事件")
    void shouldLeaveEnvironmentAloneWhenContainersExist() {
        Environment environment = busyEnvironment(1L, 1L, "services: {}\n");
        when(environmentMapper.selectByStatuses(any(), anyInt())).thenReturn(List.of(environment));
        when(containerMapper.findByEnvironmentId(1L)).thenReturn(List.of(container(1L, 1L, "cid-live")));
        when(containerLauncher.exists("cid-live")).thenReturn(true);

        reconciler.reconcile();

        verify(environmentMapper, never()).update(any(), any());
        verify(containerMapper, never()).updateById(any(Container.class));
        verify(taskService, never()).failIfRunning(any(), any());
        verify(taskService, never()).setTaskError(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("Docker 出错时不改状态，本轮只警告一次")
    void shouldNotChangeAnythingWhenDockerErrors() {
        Environment first = busyEnvironment(1L, 11L, "services: {}\n");
        Environment second = busyEnvironment(2L, 12L, "services: {}\n");
        when(environmentMapper.selectByStatuses(any(), anyInt())).thenReturn(List.of(first, second));
        when(containerMapper.findByEnvironmentId(1L))
                .thenReturn(List.of(container(1L, 1L, "gone"), container(2L, 1L, "boom")));
        when(containerMapper.findByEnvironmentId(2L)).thenReturn(List.of(container(3L, 2L, "other")));
        when(containerLauncher.exists("gone")).thenReturn(false);
        when(containerLauncher.exists("boom"))
                .thenThrow(new DockerException("Get http://10.1.2.3:2375/v1.41/containers/boom", 500));
        when(containerLauncher.exists("other"))
                .thenThrow(new DockerException("Get http://10.1.2.3:2375/v1.41/containers/other", 500));
        ListAppender<ILoggingEvent> appender = attachWarnAppender();
        try {
            reconciler.reconcile();
        } finally {
            detachWarnAppender(appender);
        }

        verify(environmentMapper, never()).update(any(), any());
        verify(containerMapper, never()).updateById(any(Container.class));
        verify(taskService, never()).lookupTask(any());
        verify(taskService, never()).failIfRunning(any(), any());
        verify(taskService, never()).setTaskError(any(), any());
        verifyNoInteractions(eventPublisher);
        assertThat(appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getFormattedMessage()).contains("本轮不改环境").contains("envId=1");
                    assertThat(event.getFormattedMessage()).doesNotContain("10.1.2.3");
                });
    }

    @Test
    @DisplayName("创建或更新时间在宽限期内的环境不核对容器")
    void shouldSkipEnvironmentsInsideGracePeriod() {
        Environment createdRecently = busyEnvironment(1L, 1L, "services: {}\n");
        createdRecently.setCreatedAt(NOW.minusSeconds(30));
        createdRecently.setUpdatedAt(NOW.minusHours(3));
        Environment updatedRecently = busyEnvironment(2L, 2L, "services: {}\n");
        updatedRecently.setCreatedAt(NOW.minusHours(3));
        updatedRecently.setUpdatedAt(NOW.minusMinutes(2).plusSeconds(1));
        Environment due = busyEnvironment(3L, 3L, "services: {}\n");
        due.setCreatedAt(NOW.minusHours(3));
        due.setUpdatedAt(NOW.minusMinutes(2));
        when(environmentMapper.selectByStatuses(any(), anyInt()))
                .thenReturn(List.of(createdRecently, updatedRecently, due));
        when(containerMapper.findByEnvironmentId(3L)).thenReturn(List.of(container(9L, 3L, "cid-due")));
        when(containerLauncher.exists("cid-due")).thenReturn(false);
        when(environmentMapper.update(isNull(), any())).thenReturn(1);

        reconciler.reconcile();

        verify(containerMapper, never()).findByEnvironmentId(1L);
        verify(containerMapper, never()).findByEnvironmentId(2L);
        verify(containerLauncher).exists("cid-due");
        verify(taskService).failIfRunning(3L, REASON);
        verify(taskService, never()).lookupTask(any());
        verify(taskService, never()).setTaskError(any(), any());
        verify(eventPublisher).publishEvent(new EnvironmentReleasedEvent(3L));
    }

    @Test
    @DisplayName("CREATING 和 RELEASING 不核对")
    void shouldSkipCreatingAndReleasing() {
        Environment creating = busyEnvironment(1L, 1L, "services: {}\n");
        creating.setStatus("CREATING");
        Environment releasing = busyEnvironment(2L, 2L, "services: {}\n");
        releasing.setStatus("RELEASING");
        when(environmentMapper.selectByStatuses(eq(EnvironmentReconciler.RECONCILE_STATUSES), anyInt()))
                .thenReturn(List.of(creating, releasing));

        reconciler.reconcile();

        verify(containerMapper, never()).findByEnvironmentId(any());
        verify(containerLauncher, never()).exists(any());
        verify(environmentMapper, never()).update(any(), any());
        verify(taskService, never()).failIfRunning(any(), any());
        verify(taskService, never()).setTaskError(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("条件更新未命中时不改任务、不改容器、不发事件")
    void shouldNotChangeTaskWhenConditionalUpdateMisses() {
        Environment environment = busyEnvironment(7L, 5L, "services: {}\n");
        when(environmentMapper.selectByStatuses(any(), anyInt())).thenReturn(List.of(environment));
        when(containerMapper.findByEnvironmentId(7L)).thenReturn(List.of(container(70L, 7L, "cid-gone")));
        when(containerLauncher.exists("cid-gone")).thenReturn(false);
        when(environmentMapper.update(isNull(), any())).thenReturn(0);

        reconciler.reconcile();

        ArgumentCaptor<Wrapper<Environment>> envUpdate = ArgumentCaptor.forClass(Wrapper.class);
        verify(environmentMapper).update(isNull(), envUpdate.capture());
        assertThat(MybatisLambdaCaches.values(envUpdate.getValue())).contains(7L, "BUSY", "ERROR");
        assertThat(((AbstractWrapper<?, ?, ?>) envUpdate.getValue()).getSqlSegment()).contains("status");
        verify(containerMapper, never()).updateById(any(Container.class));
        verify(taskService, never()).lookupTask(any());
        verify(taskService, never()).failIfRunning(any(), any());
        verify(taskService, never()).setTaskError(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("不预读任务状态，直接按 RUNNING 条件标失败，并发布释放事件")
    void shouldFailIfRunningWithoutReadingTheTask() {
        Environment environment = busyEnvironment(5L, 6L, null);
        environment.setStatus("RETAINED");
        when(environmentMapper.selectByStatuses(any(), anyInt())).thenReturn(List.of(environment));
        when(containerMapper.findByEnvironmentId(5L)).thenReturn(List.of(container(50L, 5L, "cid-old")));
        when(containerLauncher.exists("cid-old")).thenReturn(false);
        when(environmentMapper.update(isNull(), any())).thenReturn(1);

        reconciler.reconcile();

        verify(taskService).failIfRunning(6L, REASON);
        verify(taskService, never()).lookupTask(any());
        verify(taskService, never()).setTaskError(any(), any());
        verify(eventPublisher).publishEvent(new EnvironmentReleasedEvent(5L));
        verify(containerMapper).updateById(any(Container.class));
    }

    @Test
    @DisplayName("核对关闭或间隔无效时不查询环境")
    void shouldNotQueryWhenReconcileIsDisabled() {
        properties.getReconcile().setEnabled(false);
        reconciler.reconcile();
        verifyNoInteractions(environmentMapper, containerMapper, containerLauncher, taskService, eventPublisher);

        properties.getReconcile().setEnabled(true);
        properties.getReconcile().setInterval(Duration.ZERO);
        reconciler.reconcile();
        verifyNoInteractions(environmentMapper);
    }

    private static void assertMissingEnvironmentUpdate(Wrapper<Environment> wrapper,
                                                       Long environmentId,
                                                       String previousStatus,
                                                       String compose) {
        assertThat(MybatisLambdaCaches.values(wrapper))
                .contains("ERROR", NOW, environmentId, previousStatus, compose);
        String sqlSet = ((AbstractWrapper<?, ?, ?>) wrapper).getSqlSet();
        assertThat(sqlSet)
                .contains("status")
                .contains("expires_at")
                .contains("compose_content")
                .contains("port_mappings = NULL")
                .contains("resource_usage = NULL");
        assertThat(((AbstractWrapper<?, ?, ?>) wrapper).getSqlSegment()).contains("status");
    }

    private static Environment busyEnvironment(Long id, Long taskId, String compose) {
        Environment environment = new Environment();
        environment.setId(id);
        environment.setTaskId(taskId);
        environment.setStatus("BUSY");
        environment.setEnvCode("env-" + id);
        environment.setComposeContent(compose);
        environment.setPortMappings("{\"redis\":36401}");
        environment.setResourceUsage("{\"cpu\":0.1,\"memory\":32}");
        environment.setCreatedAt(NOW.minusHours(2));
        environment.setUpdatedAt(NOW.minusHours(1));
        return environment;
    }

    private static Container container(Long id, Long environmentId, String containerId) {
        Container container = new Container();
        container.setId(id);
        container.setEnvironmentId(environmentId);
        container.setContainerId(containerId);
        container.setStatus("RUNNING");
        return container;
    }

    private static ListAppender<ILoggingEvent> attachWarnAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(EnvironmentReconciler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachWarnAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(EnvironmentReconciler.class);
        logger.detachAppender(appender);
    }
}
