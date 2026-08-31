package io.github.ande1922.moduvera.scheduler.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ModuveraSchedulerAutoConfigurationTest {

    @Test
    void createsAFixedCapacitySchedulerWithoutEnablingVirtualThreads() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ModuveraSchedulerAutoConfiguration.class))
                .withPropertyValues("moduvera.scheduler.pool-size=6")
                .run(context -> {
                    assertThat(context).hasSingleBean(ThreadPoolTaskScheduler.class);
                    var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(6);
                });
    }
}
