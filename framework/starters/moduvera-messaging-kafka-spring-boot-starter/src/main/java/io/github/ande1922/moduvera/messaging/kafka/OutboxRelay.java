package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.message.outbox.OutboxPublishReport;
import io.github.ande1922.moduvera.message.outbox.OutboxWorker;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class OutboxRelay implements SmartLifecycle {

    public enum State {
        WAITING,
        RUNNING,
        STOPPING
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);

    private final Object lifecycle = new Object();
    private final OutboxWorker worker;
    private final ModuveraMessagingKafkaProperties properties;
    private final LocalOutboxWakeSignal wakeSignal;
    private final LongSupplier monotonicNanos;
    private final Runnable wakeListener = this::wake;

    private volatile boolean running;
    private volatile State state = State.WAITING;
    private boolean workSignaled;
    private ScheduledExecutorService executor;

    public OutboxRelay(
            OutboxWorker worker,
            ModuveraMessagingKafkaProperties properties,
            LocalOutboxWakeSignal wakeSignal) {
        this(worker, properties, wakeSignal, System::nanoTime);
    }

    OutboxRelay(
            OutboxWorker worker,
            ModuveraMessagingKafkaProperties properties,
            LocalOutboxWakeSignal wakeSignal,
            LongSupplier monotonicNanos) {
        this.worker = worker;
        this.properties = properties;
        this.wakeSignal = wakeSignal;
        this.monotonicNanos = monotonicNanos;
    }

    public OutboxPublishReport publishPending() {
        return worker.publishBatch(properties.getRelayBatchSize());
    }

    public void wake() {
        requestRun();
    }

    public void poll() {
        requestRun();
    }

    public State state() {
        return state;
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (running || !properties.isRelayEnabled()) {
                return;
            }
            properties.validateRelayInvariant();
            executor = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("moduvera-outbox-relay").factory());
            running = true;
            state = State.WAITING;
            workSignaled = false;
            wakeSignal.listen(wakeListener);
            long pollMillis = properties.getRelayPollInterval().toMillis();
            executor.scheduleWithFixedDelay(
                    this::pollSafely, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
        }
        wake();
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    public void stop() {
        ScheduledExecutorService stoppingExecutor;
        synchronized (lifecycle) {
            if (!running && state == State.STOPPING) {
                return;
            }
            running = false;
            state = State.STOPPING;
            workSignaled = false;
            wakeSignal.clear(wakeListener);
            stoppingExecutor = executor;
            if (stoppingExecutor != null) {
                stoppingExecutor.shutdown();
            }
        }
        awaitGrace(stoppingExecutor, properties.getShutdownGrace());
        synchronized (lifecycle) {
            executor = null;
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @SuppressWarnings("PMD.CloseResource")
    private void requestRun() {
        ScheduledExecutorService currentExecutor;
        synchronized (lifecycle) {
            if (!running || state == State.STOPPING || executor == null) {
                return;
            }
            workSignaled = true;
            if (state == State.RUNNING) {
                return;
            }
            state = State.RUNNING;
            currentExecutor = executor;
        }
        currentExecutor.execute(this::runSafely);
    }

    private void runSafely() {
        try {
            drainWithinBudget();
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Outbox relay instance {} run failed with {}",
                    properties.getRelayInstanceId(),
                    failure.getClass().getSimpleName());
            finishRun(false);
        }
    }

    private void drainWithinBudget() {
        long runStarted = monotonicNanos.getAsLong();
        boolean processedBatch = false;
        while (true) {
            synchronized (lifecycle) {
                if (state == State.STOPPING) {
                    return;
                }
                workSignaled = false;
            }
            OutboxPublishReport report = publishPending();
            if (!report.hasClaimedWork()) {
                if (finishIfNoWakeArrived()) {
                    return;
                }
                continue;
            }
            processedBatch = true;
            if (report.claimed() == properties.getRelayBatchSize()) {
                synchronized (lifecycle) {
                    workSignaled = true;
                }
            }
            if (report.leaseDeadlineReached()
                    || (processedBatch && runBudgetReached(runStarted))) {
                finishRun(true);
                return;
            }
        }
    }

    private boolean finishIfNoWakeArrived() {
        synchronized (lifecycle) {
            if (state == State.STOPPING) {
                return true;
            }
            if (workSignaled) {
                return false;
            }
            state = State.WAITING;
            return true;
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    private void finishRun(boolean preserveKnownWork) {
        ScheduledExecutorService currentExecutor = null;
        synchronized (lifecycle) {
            if (state == State.STOPPING) {
                return;
            }
            workSignaled = workSignaled || preserveKnownWork;
            if (workSignaled && executor != null) {
                workSignaled = false;
                state = State.RUNNING;
                currentExecutor = executor;
            } else {
                state = State.WAITING;
            }
        }
        if (currentExecutor != null) {
            currentExecutor.execute(this::runSafely);
        }
    }

    private boolean runBudgetReached(long runStarted) {
        long elapsed = Math.max(0L, monotonicNanos.getAsLong() - runStarted);
        return elapsed >= properties.getRelayRunBudget().toNanos();
    }

    private void pollSafely() {
        try {
            poll();
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Outbox relay instance {} poll failed with {}",
                    properties.getRelayInstanceId(),
                    failure.getClass().getSimpleName());
        }
    }

    private static void awaitGrace(
            ScheduledExecutorService stoppingExecutor, Duration shutdownGrace) {
        if (stoppingExecutor == null) {
            return;
        }
        try {
            if (!stoppingExecutor.awaitTermination(
                    shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                stoppingExecutor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stoppingExecutor.shutdownNow();
        }
    }
}
