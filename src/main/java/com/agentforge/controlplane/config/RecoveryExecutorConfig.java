package com.agentforge.controlplane.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class RecoveryExecutorConfig {
    @Bean(name = "recoveryExecutor")
    public Executor recoveryExecutor(AppSettings settings) {
        int threads = settings.getRecovery().getWorkerThreads();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(settings.getRecovery().getTenantQueueCapacity());
        executor.setThreadNamePrefix("recovery-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
