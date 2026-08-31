package io.github.ande1922.moduvera.scheduler.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("moduvera.scheduler")
public class ModuveraSchedulerProperties {

    private int poolSize = 4;
    private int shutdownAwaitSeconds = 30;

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        if (poolSize < 1 || poolSize > 256) {
            throw new IllegalArgumentException("scheduler pool size must be between 1 and 256");
        }
        this.poolSize = poolSize;
    }

    public int getShutdownAwaitSeconds() {
        return shutdownAwaitSeconds;
    }

    public void setShutdownAwaitSeconds(int shutdownAwaitSeconds) {
        if (shutdownAwaitSeconds < 0 || shutdownAwaitSeconds > 300) {
            throw new IllegalArgumentException("scheduler shutdown wait must be between 0 and 300 seconds");
        }
        this.shutdownAwaitSeconds = shutdownAwaitSeconds;
    }
}
