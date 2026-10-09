package com.sentinel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 诊断专用有界线程池，不使用应用公共执行器。
 *
 * @author Sentinel Team
 */
@Configuration
public class DiagnosisAsyncConfig {

    public static final String DIAGNOSIS_EXECUTOR = "diagnosisExecutor";

    /**
     * 核心 2、最大 4、队列 32。队列满时拒绝，由诊断服务记为失败并返回业务错误。
     */
    @Bean(name = DIAGNOSIS_EXECUTOR)
    public ThreadPoolTaskExecutor diagnosisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("diagnosis-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
