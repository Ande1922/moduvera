package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.message.outbox.OutboxAdministration;
import io.github.ande1922.moduvera.message.outbox.PublicationObserver;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class OutboxMaintenance implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxMaintenance.class);

    private final OutboxAdministration administration;
    private final PublicationObserver observer;
    private final ModuveraMessagingKafkaProperties properties;
    private final Clock clock;
    private volatile boolean running;
    private ScheduledExecutorService executor;

    public OutboxMaintenance(
            OutboxAdministration administration,
            PublicationObserver observer,
            ModuveraMessagingKafkaProperties properties,
            Clock clock) {
        this.administration = administration;
        this.observer = observer;
        this.properties = properties;
        this.clock = clock;
    }

    public int runOnce() {
        int deleted = administration.deletePublishedBefore(
                clock.instant().minus(properties.getPublishedRetention()),
                properties.getCleanupBatchSize());
        observer.cleanup(deleted);
        observer.backlog(administration.backlog());
        return deleted;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("moduvera-outbox-maintenance").factory());
        long interval = properties.getMaintenanceInterval().toMillis();
        executor.scheduleWithFixedDelay(this::runSafely, interval, interval, TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void runSafely() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            LOGGER.warn("Outbox maintenance failed with {}", failure.getClass().getSimpleName());
        }
    }
}
