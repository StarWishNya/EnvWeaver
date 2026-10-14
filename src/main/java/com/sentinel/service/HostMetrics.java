package com.sentinel.service;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.DoubleSupplier;

/**
 * 主机 CPU、物理内存，以及工作目录所在文件系统。
 */
interface HostMetrics {

    int availableProcessors();

    /**
     * 系统 CPU 负载，JDK 约定为 0 到 1。不可用时为负数。
     * 本机实现会先预热一次，避免第一次读数是 0。
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

        /**
         * 给 JDK 留出两次 CPU 采样的间隔。第一次 getCpuLoad 经常是 0。
         */
        private static final long CPU_WARMUP_MILLIS = 200L;

        private final CpuLoadSamples cpuLoadSamples = new CpuLoadSamples();
        private boolean cpuWarmed;

        @Override
        public int availableProcessors() {
            OperatingSystemMXBean os = operatingSystem();
            if (os == null) {
                return 0;
            }
            return os.getAvailableProcessors();
        }

        @Override
        public synchronized double systemCpuLoad() {
            if (!cpuWarmed) {
                cpuWarmed = true;
                double first = readRawCpuLoad();
                if (CpuLoadSamples.isPositive(first)) {
                    return cpuLoadSamples.observe(first, false);
                }
                if (first == 0d || Double.isNaN(first)) {
                    sleepQuietly(CPU_WARMUP_MILLIS);
                    return cpuLoadSamples.observe(readRawCpuLoad(), true);
                }
                return cpuLoadSamples.observe(first, false);
            }
            return cpuLoadSamples.observe(readRawCpuLoad(), true);
        }

        private double readRawCpuLoad() {
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

        private static void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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

    /**
     * 连续 CPU 采样。第一次出现的 0 视为还没有采样间隔。
     * NaN、无穷大或负数在已有样本时回退到上一次有效值，否则为 -1。
     */
    final class CpuLoadSamples {

        private double lastValid = Double.NaN;

        synchronized boolean hasSample() {
            return !Double.isNaN(lastValid);
        }

        /**
         * @param zeroAllowed 为 false 时，0 视为首次采样尚未建立
         */
        synchronized double observe(double raw, boolean zeroAllowed) {
            if (isPositive(raw) || (zeroAllowed && isZero(raw))) {
                lastValid = raw;
                return raw;
            }
            if (hasSample()) {
                return lastValid;
            }
            return -1d;
        }

        synchronized void prime(DoubleSupplier supplier) {
            observe(read(supplier), false);
            if (!hasSample()) {
                observe(read(supplier), true);
            }
        }

        private static double read(DoubleSupplier supplier) {
            if (supplier == null) {
                return -1d;
            }
            try {
                return supplier.getAsDouble();
            } catch (Exception e) {
                return -1d;
            }
        }

        static boolean isPositive(double raw) {
            return !Double.isNaN(raw) && !Double.isInfinite(raw) && raw > 0d;
        }

        static boolean isZero(double raw) {
            return raw == 0d;
        }
    }
}
