package com.sentinel.service.provision;

import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.EnvironmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

/**
 * 端口分配：跳过已占用和无法绑定的端口，并发时不重复。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("端口分配")
class PortAllocatorTest {

    @Mock
    private EnvironmentMapper environmentMapper;

    private ProvisioningProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ProvisioningProperties();
        properties.setPortRange("30000-30010");
    }

    @Test
    @DisplayName("跳过非空闲环境已占用的端口，以及绑定失败的端口")
    void skipsUsedAndUnbindablePorts() {
        when(environmentMapper.selectActivePortMappings()).thenReturn(java.util.Arrays.asList(
                "{\"mysql\":30000}",
                "{\"redis\":\"30001\"}",
                "{\"nginx\":\"80:30002\"}",
                "not-json",
                null));
        PortAllocator allocator = allocator(port -> port != 30003);

        assertThat(allocator.allocate(2)).containsExactly(30004, 30005);
    }

    @Test
    @DisplayName("未释放的端口不会再次分出，释放后可以复用")
    void reservationPreventsReuseUntilReleased() {
        when(environmentMapper.selectActivePortMappings()).thenReturn(List.of());
        PortAllocator allocator = allocator(port -> true);

        assertThat(allocator.allocate(1)).containsExactly(30000);
        assertThat(allocator.allocate(1)).containsExactly(30001);
        allocator.release(List.of(30000));
        assertThat(allocator.allocate(1)).containsExactly(30000);
    }

    @Test
    @DisplayName("分配失败不会占住端口")
    void failedAllocationDoesNotReservePorts() {
        when(environmentMapper.selectActivePortMappings()).thenReturn(List.of("{\"mysql\":30000}"), List.of());
        properties.setPortRange("30000-30000");
        PortAllocator allocator = allocator(port -> true);

        BusinessException exhausted = catchThrowableOfType(() -> allocator.allocate(1), BusinessException.class);
        assertThat(exhausted).isNotNull();
        assertThat(exhausted.getMessage()).isEqualTo("端口范围内没有可用端口");
        assertThat(exhausted.getCode()).isEqualTo(ErrorCode.ENVIRONMENT_CREATE_FAILED.getCode());

        assertThat(allocator.allocate(1)).containsExactly(30000);
    }

    @Test
    @DisplayName("端口范围配置无效")
    void rejectsInvalidPortRange() {
        PortAllocator allocator = allocator(port -> true);
        for (String range : List.of("", "30000", "40000-30000", "0-10", "30000-70000", "abc-def")) {
            properties.setPortRange(range);
            assertThatThrownBy(() -> allocator.allocate(1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("端口范围配置无效");
        }
    }

    @Test
    @DisplayName("并发分配串行执行且端口互不重复")
    void concurrentAllocationsDoNotCollide() throws Exception {
        when(environmentMapper.selectActivePortMappings()).thenReturn(List.of());
        properties.setPortRange("30000-30020");
        AtomicInteger insideProbe = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean();
        PortAllocator allocator = allocator(port -> {
            if (port == 30000) {
                return false;
            }
            if (insideProbe.incrementAndGet() > 1) {
                overlapped.set(true);
            }
            try {
                Thread.sleep(30);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                insideProbe.decrementAndGet();
            }
            return true;
        });

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> allocator.allocate(1).get(0)));
            }
            Set<Integer> ports = new HashSet<>();
            for (Future<Integer> future : futures) {
                ports.add(future.get(5, TimeUnit.SECONDS));
            }
            assertThat(ports).hasSize(4);
            assertThat(ports).doesNotContain(30000);
            assertThat(overlapped).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("本地 bind 探测能识别被占用的端口")
    void bindProbeSkipsPortHeldByThisProcess() throws Exception {
        try (ServerSocket held = new ServerSocket()) {
            held.setReuseAddress(false);
            held.bind(new InetSocketAddress("0.0.0.0", 0));
            int heldPort = held.getLocalPort();
            BindPortProbe probe = new BindPortProbe();
            assertThat(probe.canBind(heldPort)).isFalse();
        }

        int freePort;
        try (ServerSocket ephemeral = new ServerSocket(0)) {
            freePort = ephemeral.getLocalPort();
        }
        assertThat(new BindPortProbe().canBind(freePort)).isTrue();
    }

    private PortAllocator allocator(PortProbe probe) {
        return new PortAllocator(environmentMapper, properties, probe);
    }
}
