package com.sentinel.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统资源指标，字段与 OpenAPI SystemMetrics 一致。
 *
 * @author Sentinel Team
 */
@Data
public class SystemMetrics {

    private LocalDateTime timestamp;

    private Cpu cpu;

    private Memory memory;

    private Disk disk;

    private Containers containers;

    /**
     * CPU 使用率 0-100；采集不到时 usage 为 -1。cores 采集不到时为 0。
     */
    @Data
    public static class Cpu {
        private double usage;
        private int cores;
    }

    /**
     * 物理内存，单位字节。usage 为 0-100。
     */
    @Data
    public static class Memory {
        private long used;
        private long total;
        private double usage;
    }

    /**
     * 工作目录所在文件系统，单位字节。usage 为 0-100。
     */
    @Data
    public static class Disk {
        private long used;
        private long total;
        private double usage;
    }

    /**
     * Docker 容器数量。
     */
    @Data
    public static class Containers {
        private int running;
        private int paused;
        private int stopped;

        public static Containers zeros() {
            return new Containers();
        }
    }
}
