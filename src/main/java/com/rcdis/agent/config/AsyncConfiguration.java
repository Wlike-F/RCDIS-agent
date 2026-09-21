package com.rcdis.agent.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;

@Configuration
@EnableAsync
public class AsyncConfiguration {

    @Bean(name = "sseTaskExecutor")
    public Executor sseTaskExecutor() {
        return buildExecutor("rcdis-sse-", 2, 8, 100);
    }

    /**
     * Runs outbound Feishu notifications for latency-bounded callers. The Feishu card callback must
     * answer within 3 seconds, so the state change is committed first and the notification, which
     * involves an external HTTP round trip with retries, is handed to this executor.
     */
    @Bean(name = "notificationTaskExecutor")
    public Executor notificationTaskExecutor() {
        return buildExecutor("rcdis-notify-", 2, 8, 200);
    }

    /**
     * Runs context compression (rolling summary + facts) off the request thread so compressing a
     * long conversation never adds latency to a chat turn.
     */
    @Bean(name = "memoryTaskExecutor")
    public Executor memoryTaskExecutor() {
        return buildExecutor("rcdis-memory-", 1, 4, 100);
    }

    /**
     * Runs vision-model receipt recognition right after upload so the HTTP upload returns fast;
     * recognition latency and failures never affect the upload response.
     */
    @Bean(name = "ocrTaskExecutor")
    public Executor ocrTaskExecutor() {
        return buildExecutor("rcdis-ocr-", 1, 2, 100);
    }

    /**
     * Both executors propagate the current user so that audit entries written on the worker thread
     * still record the real actor.
     */
    private Executor buildExecutor(String threadNamePrefix, int corePoolSize, int maxPoolSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
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
