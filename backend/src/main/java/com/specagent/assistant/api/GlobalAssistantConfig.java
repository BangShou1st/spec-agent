package com.specagent.assistant.api;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 文件名:GlobalAssistantConfig.java
 *
 * 用途:为全局助手 Run 的执行提供有界异步线程池(gaExecutor)。
 * 传输层断连不会取消 Run:执行生命周期独立于任何单个 SSE 订阅而存在。
 *
 * 角色:api 层的基础设施配置,run 创建入口把执行任务提交到这里排队执行。
 * 按当前单实例规模做了保守限额:最多 4 个并发 Run,队列容量 50;队列打满时
 * 以类型化的 Run 失败(Reject)收场,而不是无限扩张 OS 线程。优雅停机时
 * 最多等待 30 秒让在途 Run 跑完。
 */
@Configuration
public class GlobalAssistantConfig {
    @Bean(destroyMethod = "shutdown")
    public Executor gaExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("global-assistant-run-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
