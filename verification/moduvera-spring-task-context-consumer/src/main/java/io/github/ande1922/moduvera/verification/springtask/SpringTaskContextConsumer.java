package io.github.ande1922.moduvera.verification.springtask;

import io.github.ande1922.moduvera.context.spring.ExecutionContextTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Independent Spring consumer that explicitly selects which task executor propagates context. */
@Configuration(proxyBeanMethods = false)
@EnableAsync(proxyTargetClass = true)
public class SpringTaskContextConsumer {

    public static final String PROPAGATING_EXECUTOR = "propagatingTaskExecutor";
    public static final String PLAIN_EXECUTOR = "plainTaskExecutor";

    @Bean(PROPAGATING_EXECUTOR)
    ThreadPoolTaskExecutor propagatingTaskExecutor() {
        ThreadPoolTaskExecutor executor = singleWorkerExecutor("context-worker-");
        executor.setTaskDecorator(new ExecutionContextTaskDecorator());
        return executor;
    }

    @Bean(PLAIN_EXECUTOR)
    ThreadPoolTaskExecutor plainTaskExecutor() {
        return singleWorkerExecutor("plain-worker-");
    }

    @Bean
    AsyncContextProbe asyncContextProbe() {
        return new AsyncContextProbe();
    }

    private static ThreadPoolTaskExecutor singleWorkerExecutor(String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix(threadNamePrefix);
        return executor;
    }
}
