package com.sentinel.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 生命周期专用有界线程池，不使用应用公共执行器。
 * 队列满时丢弃任务并记警告，PENDING 留给补扫，过期环境留给回收器。
 *
 * @author Sentinel Team
 */
@Slf4j
@Configuration
public class LifecycleAsyncConfig {

    public static final String LIFECYCLE_EXECUTOR = "lifecycleExecutor";

    /**
     * 核心 2、最大 4、队列 100。拒绝时不抛异常，避免提交线程被打断。
     */
    @Bean(name = LIFECYCLE_EXECUTOR)
    public ThreadPoolTaskExecutor lifecycleExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("lifecycle-");
        executor.setRejectedExecutionHandler(this::onRejected);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    private void onRejected(Runnable task, ThreadPoolExecutor pool) {
        log.warn("生命周期线程池已满，任务留给扫尾处理: active={}, queueSize={}, poolSize={}",
                pool.getActiveCount(), pool.getQueue().size(), pool.getPoolSize());
    }
}
