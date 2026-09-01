package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.outbox.OutboxBacklog;
import io.github.ande1922.moduvera.message.outbox.PublicationObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class MicrometerPublicationObserver implements PublicationObserver {

    private final MeterRegistry registry;
    private final Counter claimed;
    private final Counter claimConflicts;
    private final Counter cleanup;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();
    private final AtomicLong terminal = new AtomicLong();

    public MicrometerPublicationObserver(MeterRegistry registry) {
        this.registry = registry;
        claimed = registry.counter("moduvera.messaging.outbox.claimed");
        claimConflicts = registry.counter("moduvera.messaging.outbox.claim.conflicts");
        cleanup = registry.counter("moduvera.messaging.outbox.cleanup.deleted");
        Gauge.builder("moduvera.messaging.outbox.pending", pending, AtomicLong::get)
                .register(registry);
        Gauge.builder(
                        "moduvera.messaging.outbox.pending.oldest.seconds",
                        oldestPendingSeconds,
                        AtomicLong::get)
                .register(registry);
        Gauge.builder("moduvera.messaging.outbox.terminal", terminal, AtomicLong::get)
                .register(registry);
    }

    @Override
    public void claimed(int count) {
        claimed.increment(count);
    }

    @Override
    public void completed(SerializedMessage message, Result result, Duration brokerLatency) {
        String destination = message.descriptor().destination().value();
        String messageType = message.descriptor().type().value();
        String resultTag = result.name().toLowerCase(Locale.ROOT);
        registry.counter(
                        "moduvera.messaging.outbox.publish",
                        "destination",
                        destination,
                        "type",
                        messageType,
                        "result",
                        resultTag)
                .increment();
        Timer.builder("moduvera.messaging.outbox.broker.ack")
                .tags(
                        "destination", destination,
                        "type", messageType,
                        "result", resultTag)
                .register(registry)
                .record(brokerLatency);
    }

    @Override
    public void staleToken(String operation) {
        registry.counter("moduvera.messaging.outbox.stale.token", "operation", operation)
                .increment();
    }

    @Override
    public void claimConflict() {
        claimConflicts.increment();
    }

    @Override
    public void cleanup(int deleted) {
        cleanup.increment(deleted);
    }

    @Override
    public void backlog(OutboxBacklog backlog) {
        pending.set(backlog.pendingCount());
        oldestPendingSeconds.set(backlog.oldestPendingAge().toSeconds());
        terminal.set(backlog.terminalCount());
    }
}
