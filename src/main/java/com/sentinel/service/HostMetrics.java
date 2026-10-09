package com.sentinel.service;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 主机 CPU、物理内存，以及工作目录所在文件系统。
 */
interface HostMetrics {

    int availableProcessors();

    /**
     * 系统 CPU 负载，JDK 约定为 0 到 1。不可用时为负数。
     */
    double systemCpuLoad();

    long totalPhysicalMemory();

    long freePhysicalMemory();

    DiskSpace disk();

    /**
     * 文件系统总字节和未分配字节。
     */
    record DiskSpace(long totalBytes, long unallocatedBytes) {
    }

    static HostMetrics system() {
        return new SystemHostMetrics();
    }

    /**
     * 读取本机指标。单项失败时给出不可用哨兵值，不把异常抛给接口。
     */
    @Slf4j
    final class SystemHostMetrics implements HostMetrics {

        @Override
        public int availableProcessors() {
            OperatingSystemMXBean os = operatingSystem();
            if (os == null) {
                return 0;
            }
            return os.getAvailableProcessors();
        }

        @Override
        public double systemCpuLoad() {
            OperatingSystemMXBean os = operatingSystem();
            if (!(os instanceof com.sun.management.OperatingSystemMXBean sun)) {
                return -1d;
            }
            try {
                return sun.getCpuLoad();
            } catch (Exception e) {
                log.warn("CPU load unavailable: {}", e.getClass().getSimpleName());
                return -1d;
            }
        }

        @Override
        public long totalPhysicalMemory() {
            return memorySize(true);
        }

        @Override
        public long freePhysicalMemory() {
            return memorySize(false);
        }

        @Override
        public DiskSpace disk() {
            return readDisk(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath());
        }

        private static long memorySize(boolean total) {
            OperatingSystemMXBean os = operatingSystem();
            if (!(os instanceof com.sun.management.OperatingSystemMXBean sun)) {
                return -1L;
            }
            try {
                return total ? sun.getTotalMemorySize() : sun.getFreeMemorySize();
            } catch (Exception e) {
                log.warn("Memory metrics unavailable: {}", e.getClass().getSimpleName());
                return -1L;
            }
        }

        static DiskSpace readDisk(Path directory) {
            try {
                FileStore store = Files.getFileStore(directory);
                return new DiskSpace(store.getTotalSpace(), store.getUnallocatedSpace());
            } catch (IOException | RuntimeException e) {
                log.warn("Disk metrics unavailable: {}", e.getClass().getSimpleName());
                return new DiskSpace(-1L, -1L);
            }
        }

        private static OperatingSystemMXBean operatingSystem() {
            try {
                return ManagementFactory.getOperatingSystemMXBean();
            } catch (Exception e) {
                log.warn("Operating system MXBean unavailable: {}", e.getClass().getSimpleName());
                return null;
            }
        }
    }
}
