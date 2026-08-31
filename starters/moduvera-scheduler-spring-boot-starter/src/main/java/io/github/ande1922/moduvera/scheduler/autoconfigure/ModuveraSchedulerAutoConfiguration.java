package io.github.ande1922.moduvera.scheduler.autoconfigure;

import io.github.ande1922.moduvera.lock.LockTemplate;
import io.github.ande1922.moduvera.scheduler.JobRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@AutoConfiguration
@EnableConfigurationProperties(ModuveraSchedulerProperties.class)
public class ModuveraSchedulerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "moduveraTaskScheduler")
    ThreadPoolTaskScheduler moduveraTaskScheduler(ModuveraSchedulerProperties properties) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getPoolSize());
        scheduler.setThreadNamePrefix("moduvera-job-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(properties.getShutdownAwaitSeconds());
        return scheduler;
    }

    @Bean
    @ConditionalOnBean(LockTemplate.class)
    @ConditionalOnMissingBean
    JobRunner moduveraJobRunner(LockTemplate locks) {
        return new JobRunner(locks);
    }
}
