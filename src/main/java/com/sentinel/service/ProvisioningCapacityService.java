package com.sentinel.service;

import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.domain.Task;
import com.sentinel.mapper.ContainerMapper;
import com.sentinel.mapper.EnvironmentMapper;
import com.sentinel.mapper.TaskMapper;
import com.sentinel.service.provision.DependencyCatalog;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 按数据库里的占用环境，加上本进程预留，判断能不能再供给。
 * 预留只在单机有效。多实例要换分布式锁，Redis 可通过 {@link CacheService} 使用。
 */
@Service
public class ProvisioningCapacityService implements ProvisioningCapacity {

    private final EnvironmentMapper environmentMapper;
    private final ContainerMapper containerMapper;
    private final TaskMapper taskMapper;
    private final DependencyCatalog dependencyCatalog;
    private final ProvisioningProperties provisioningProperties;
    private final Object reservationsMonitor = new Object();
    private final Map<Long, Integer> reservations = new HashMap<>();

    public ProvisioningCapacityService(EnvironmentMapper environmentMapper,
                                       ContainerMapper containerMapper,
                                       TaskMapper taskMapper,
                                       DependencyCatalog dependencyCatalog,
                                       ProvisioningProperties provisioningProperties) {
        this.environmentMapper = environmentMapper;
        this.containerMapper = containerMapper;
        this.taskMapper = taskMapper;
        this.dependencyCatalog = dependencyCatalog;
        this.provisioningProperties = provisioningProperties;
    }

    @Override
    public boolean wouldExceed(Long taskId) {
        int needed = containersNeeded(taskId);
        int environments = count(environmentMapper.countOccupying());
        int containers = count(containerMapper.countInOccupyingEnvironments());
        int[] extra = extraBeyondDatabase();
        return ProvisioningLimitDecider.exceeds(
                environments + extra[0],
                containers + extra[1],
                needed,
                maxActiveEnvironments(),
                maxContainers());
    }

    @Override
    public int containersNeeded(Long taskId) {
        if (taskId == null || taskMapper == null) {
            return 0;
        }
        Task task = taskMapper.selectById(taskId);
        if (task == null || dependencyCatalog == null) {
            return 0;
        }
        try {
            return dependencyCatalog.resolve(task.getDependencies()).size();
        } catch (BusinessException ex) {
            return 0;
        }
    }

    @Override
    public void reserve(Long taskId, int containers) {
        if (taskId == null) {
            return;
        }
        synchronized (reservationsMonitor) {
            reservations.put(taskId, Math.max(containers, 0));
        }
    }

    @Override
    public void release(Long taskId) {
        if (taskId == null) {
            return;
        }
        synchronized (reservationsMonitor) {
            reservations.remove(taskId);
        }
    }

    @Override
    public ProvisioningUsage usage() {
        return new ProvisioningUsage(
                count(environmentMapper.countOccupying()),
                count(containerMapper.countInOccupyingEnvironments()),
                count(taskMapper.countWaitingQueued()),
                maxActiveEnvironments(),
                maxContainers());
    }

    /**
     * 预留里还没被占用环境行覆盖的部分。
     * 环境行已经在、但容器行还没插齐时，只补差额，避免和库内计数重复。
     */
    private int[] extraBeyondDatabase() {
        Map<Long, Integer> snapshot;
        synchronized (reservationsMonitor) {
            snapshot = new HashMap<>(reservations);
        }
        int environments = 0;
        int containers = 0;
        for (Map.Entry<Long, Integer> entry : snapshot.entrySet()) {
            Long taskId = entry.getKey();
            int reservedContainers = entry.getValue() == null ? 0 : entry.getValue();
            int occupying = count(environmentMapper.countOccupyingByTaskId(taskId));
            if (occupying == 0) {
                environments++;
                containers += reservedContainers;
                continue;
            }
            int recorded = count(containerMapper.countInOccupyingEnvironmentsByTaskId(taskId));
            if (recorded < reservedContainers) {
                containers += reservedContainers - recorded;
            }
        }
        return new int[]{environments, containers};
    }

    private int maxActiveEnvironments() {
        ProvisioningProperties.Limits limits = limits();
        return limits.getMaxActiveEnvironments();
    }

    private int maxContainers() {
        return limits().getMaxContainers();
    }

    private ProvisioningProperties.Limits limits() {
        if (provisioningProperties == null || provisioningProperties.getLimits() == null) {
            return new ProvisioningProperties.Limits();
        }
        return provisioningProperties.getLimits();
    }

    private static int count(Integer value) {
        return value == null ? 0 : value;
    }
}
