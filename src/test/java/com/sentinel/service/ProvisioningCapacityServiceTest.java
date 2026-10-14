package com.sentinel.service;

import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.domain.Task;
import com.sentinel.mapper.ContainerMapper;
import com.sentinel.mapper.EnvironmentMapper;
import com.sentinel.mapper.TaskMapper;
import com.sentinel.service.provision.DependencyCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 占用计数、预留和依赖容器数。不访问数据库。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("供给容量")
class ProvisioningCapacityServiceTest {

    @Mock
    private EnvironmentMapper environmentMapper;

    @Mock
    private ContainerMapper containerMapper;

    @Mock
    private TaskMapper taskMapper;

    private DependencyCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new DependencyCatalog(new ProvisioningProperties());
    }

    @Test
    @DisplayName("环境数达到上限时拒绝，差一个时允许")
    void rejectsWhenAnotherEnvironmentWouldExceed() {
        when(environmentMapper.countOccupying()).thenReturn(10);
        when(containerMapper.countInOccupyingEnvironments()).thenReturn(0);
        when(taskMapper.selectById(1L)).thenReturn(task(1L, "[]"));

        assertThat(service(10, 30).wouldExceed(1L)).isTrue();

        when(environmentMapper.countOccupying()).thenReturn(9);
        assertThat(service(10, 30).wouldExceed(1L)).isFalse();
    }

    @Test
    @DisplayName("容器上限计入本次任务需要的容器")
    void rejectsWhenContainersWouldExceed() {
        when(environmentMapper.countOccupying()).thenReturn(1);
        when(containerMapper.countInOccupyingEnvironments()).thenReturn(29);
        when(taskMapper.selectById(2L)).thenReturn(task(2L, "[\"mysql\",\"redis\"]"));

        assertThat(service(10, 30).wouldExceed(2L)).isTrue();

        when(containerMapper.countInOccupyingEnvironments()).thenReturn(28);
        assertThat(service(10, 30).wouldExceed(2L)).isFalse();
    }

    @Test
    @DisplayName("0 和负数上限都不限制")
    void unlimitedWhenLimitIsZeroOrNegative() {
        when(environmentMapper.countOccupying()).thenReturn(40);
        when(containerMapper.countInOccupyingEnvironments()).thenReturn(80);
        when(taskMapper.selectById(3L)).thenReturn(task(3L, "[\"redis\"]"));

        assertThat(service(0, 0).wouldExceed(3L)).isFalse();
        assertThat(service(-1, -2).wouldExceed(3L)).isFalse();
    }

    @Test
    @DisplayName("预留在环境行出现前计入占用，释放后不再计入")
    void reservationCountsUntilEnvironmentRowExists() {
        when(environmentMapper.countOccupying()).thenReturn(0);
        when(containerMapper.countInOccupyingEnvironments()).thenReturn(0);
        when(environmentMapper.countOccupyingByTaskId(8L)).thenReturn(0);
        when(taskMapper.selectById(9L)).thenReturn(task(9L, "[\"redis\"]"));

        ProvisioningCapacityService capacity = service(1, 30);
        capacity.reserve(8L, 1);

        assertThat(capacity.wouldExceed(9L)).isTrue();

        capacity.release(8L);
        assertThat(capacity.wouldExceed(9L)).isFalse();
    }

    @Test
    @DisplayName("环境行和容器行已经落库时不把预留再加一遍")
    void doesNotDoubleCountReservationAlreadyInDatabase() {
        when(environmentMapper.countOccupying()).thenReturn(1);
        when(containerMapper.countInOccupyingEnvironments()).thenReturn(2);
        when(environmentMapper.countOccupyingByTaskId(8L)).thenReturn(1);
        when(containerMapper.countInOccupyingEnvironmentsByTaskId(8L)).thenReturn(2);
        when(taskMapper.selectById(9L)).thenReturn(task(9L, "[]"));

        ProvisioningCapacityService capacity = service(2, 30);
        capacity.reserve(8L, 2);

        assertThat(capacity.wouldExceed(9L)).isFalse();
    }

    @Test
    @DisplayName("环境已在、容器行还没插齐时只补差额")
    void addsOnlyTheContainerGap() {
        when(environmentMapper.countOccupying()).thenReturn(1);
        when(containerMapper.countInOccupyingEnvironments()).thenReturn(0);
        when(environmentMapper.countOccupyingByTaskId(8L)).thenReturn(1);
        when(containerMapper.countInOccupyingEnvironmentsByTaskId(8L)).thenReturn(0);
        when(taskMapper.selectById(9L)).thenReturn(task(9L, "[\"redis\"]"));

        ProvisioningCapacityService capacity = service(10, 2);
        capacity.reserve(8L, 2);

        assertThat(capacity.wouldExceed(9L)).isTrue();
    }

    @Test
    @DisplayName("指标只看库内占用，不看尚未落库的预留")
    void usageIgnoresReservations() {
        when(environmentMapper.countOccupying()).thenReturn(3);
        when(containerMapper.countInOccupyingEnvironments()).thenReturn(5);
        when(taskMapper.countWaitingQueued()).thenReturn(4);

        ProvisioningCapacityService capacity = service(10, 30);
        capacity.reserve(8L, 2);

        assertThat(capacity.usage()).isEqualTo(new ProvisioningUsage(3, 5, 4, 10, 30));
    }

    @Test
    @DisplayName("redis 算 1 个容器，重复种类只算一次，空依赖和未知依赖算 0")
    void countsResolvedContainers() {
        ProvisioningCapacityService capacity = service(10, 30);
        when(taskMapper.selectById(1L)).thenReturn(task(1L, "[\"redis\"]"));
        when(taskMapper.selectById(2L)).thenReturn(task(2L, "[\"redis\",\"redis\"]"));
        when(taskMapper.selectById(3L)).thenReturn(task(3L, "[]"));
        when(taskMapper.selectById(4L)).thenReturn(task(4L, "[\"not-a-middleware\"]"));
        when(taskMapper.selectById(5L)).thenReturn(null);

        assertThat(capacity.containersNeeded(1L)).isEqualTo(1);
        assertThat(capacity.containersNeeded(2L)).isEqualTo(1);
        assertThat(capacity.containersNeeded(3L)).isZero();
        assertThat(capacity.containersNeeded(4L)).isZero();
        assertThat(capacity.containersNeeded(5L)).isZero();
        assertThat(capacity.containersNeeded(null)).isZero();
    }

    @Test
    @DisplayName("计数为 null 或缺少上限配置时按 0 和默认上限")
    void nullCountsAndMissingLimits() {
        when(environmentMapper.countOccupying()).thenReturn(null);
        when(containerMapper.countInOccupyingEnvironments()).thenReturn(null);
        when(taskMapper.countWaitingQueued()).thenReturn(null);
        when(taskMapper.selectById(1L)).thenReturn(task(1L, "[]"));

        ProvisioningProperties properties = new ProvisioningProperties();
        properties.setLimits(null);
        ProvisioningCapacityService capacity = new ProvisioningCapacityService(
                environmentMapper, containerMapper, taskMapper, catalog, properties);

        assertThat(capacity.wouldExceed(1L)).isFalse();
        assertThat(capacity.usage()).isEqualTo(ProvisioningUsage.defaults());

        ProvisioningCapacityService unlimited = new ProvisioningCapacityService(
                environmentMapper, containerMapper, taskMapper, catalog, null);
        assertThat(unlimited.usage().maxActiveEnvironments()).isEqualTo(10);
        assertThat(unlimited.wouldExceed(1L)).isFalse();
    }

    private ProvisioningCapacityService service(int maxEnvironments, int maxContainers) {
        ProvisioningProperties properties = new ProvisioningProperties();
        properties.getLimits().setMaxActiveEnvironments(maxEnvironments);
        properties.getLimits().setMaxContainers(maxContainers);
        return new ProvisioningCapacityService(
                environmentMapper, containerMapper, taskMapper, catalog, properties);
    }

    private static Task task(Long id, String dependencies) {
        Task task = new Task();
        task.setId(id);
        task.setDependencies(dependencies);
        task.setStatus("PENDING");
        return task;
    }
}
