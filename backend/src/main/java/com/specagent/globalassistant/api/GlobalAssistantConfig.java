package com.specagent.globalassistant.api;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bounded async executor for Global Assistant runs. Transport disconnect
 * never cancels the run: execution outlives any single SSE subscription.
 */
@Configuration
public class GlobalAssistantConfig {
    @Bean
    public Executor gaExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "global-assistant-run");
            thread.setDaemon(true);
            return thread;
        });
    }
}
