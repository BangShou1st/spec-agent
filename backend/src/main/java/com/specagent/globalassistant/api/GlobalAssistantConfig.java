package com.specagent.globalassistant.api;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bounded async executor for Global Assistant runs. Transport disconnect
 * never cancels the run: execution outlives any single SSE subscription.
 *
 * <p>Conservative bounds for the current single-instance scale: at most 4
 * concurrent runs with a queue of 50. Saturation rejects with a typed run
 * failure instead of growing OS threads without bound. Graceful shutdown
 * waits up to 30 seconds for in-flight runs.
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
