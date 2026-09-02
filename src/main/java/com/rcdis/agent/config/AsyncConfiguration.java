package com.rcdis.agent.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;

@Configuration
public class AsyncConfiguration {

    @Bean(name = "sseTaskExecutor")
    public Executor sseTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("rcdis-sse-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setTaskDecorator(task -> {
            CurrentUserTO capturedUser = CurrentUserContextHolder.currentOrNull();
            return () -> {
                if (capturedUser != null) {
                    CurrentUserContextHolder.set(capturedUser);
                }
                try {
                    task.run();
                } finally {
                    CurrentUserContextHolder.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }
}
