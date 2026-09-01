package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.outbox.OutboxWorker;
import io.github.ande1922.moduvera.message.outbox.memory.InMemoryDurablePublication;
import io.github.ande1922.moduvera.message.outbox.memory.InMemoryOutboxStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void oneWakeContinuouslyDrainsMultipleBatches() throws Exception {
        var store = new InMemoryOutboxStore(CLOCK);
        AtomicInteger sends = new AtomicInteger();
        var relay = relay(store, ignored -> sends.incrementAndGet(), properties());
        relay.start();
        try {
            await(() -> relay.state() == OutboxRelay.State.WAITING);
            append(store, message("msg-1", "order-1"));
            append(store, message("msg-2", "order-2"));
            append(store, message("msg-3", "order-3"));

            relay.wake();

            await(() -> sends.get() == 3 && relay.state() == OutboxRelay.State.WAITING);
            assertThat(store.isPublished(new MessageId("msg-1"))).isTrue();
            assertThat(store.isPublished(new MessageId("msg-2"))).isTrue();
            assertThat(store.isPublished(new MessageId("msg-3"))).isTrue();
        } finally {
            relay.stop();
        }
    }

    @Test
    void concurrentWakeAndPollSignalsNeverOverlapWorkers() throws Exception {
        var store = new InMemoryOutboxStore(CLOCK);
        for (int index = 0; index < 20; index++) {
            append(store, message("msg-" + index, "order-" + index));
        }
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        var relay = relay(
                store,
                ignored -> {
                    int current = active.incrementAndGet();
                    maximumActive.accumulateAndGet(current, Math::max);
                    try {
                        sends.incrementAndGet();
                    } finally {
                        active.decrementAndGet();
                    }
                },
                properties());
        relay.start();
        try (var callers = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<?>> signals = new ArrayList<>();
            for (int index = 0; index < 40; index++) {
                signals.add(callers.submit(index % 2 == 0 ? relay::wake : relay::poll));
            }
            for (var signal : signals) {
                signal.get();
            }
            await(() -> sends.get() == 20 && relay.state() == OutboxRelay.State.WAITING);
            assertThat(maximumActive).hasValue(1);
        } finally {
            relay.stop();
        }
    }

    @Test
    void stoppingFinishesTheStartedSendButDoesNotClaimMoreWork() throws Exception {
        var store = new InMemoryOutboxStore(CLOCK);
        append(store, message("msg-started", "order-1"));
        append(store, message("msg-unclaimed", "order-2"));
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        var relay = relay(
                store,
                ignored -> {
                    sendStarted.countDown();
                    try {
                        if (!releaseSend.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test send was not released");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                },
                properties());
        relay.start();
        assertThat(sendStarted.await(2, TimeUnit.SECONDS)).isTrue();
        try (var stopper = Executors.newSingleThreadExecutor()) {
            var stopped = stopper.submit((Runnable) relay::stop);
            await(() -> relay.state() == OutboxRelay.State.STOPPING);
            relay.wake();
            releaseSend.countDown();
            stopped.get(2, TimeUnit.SECONDS);
        }

        assertThat(store.isPublished(new MessageId("msg-started"))).isTrue();
        assertThat(store.isPublished(new MessageId("msg-unclaimed"))).isFalse();
    }

    @Test
    void forcedStopLeavesAnInterruptedUnknownSendClaimedForLeaseTakeover() throws Exception {
        var store = new InMemoryOutboxStore(CLOCK);
        append(store, message("msg-interrupted", "order-1"));
        CountDownLatch sendStarted = new CountDownLatch(1);
        var properties = properties();
        var worker = new OutboxWorker(
                store,
                ignored -> {
                    sendStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                },
                CLOCK,
                properties.getClaimLease(),
                Duration.ZERO,
                1);
        var relay = new OutboxRelay(worker, properties, new LocalOutboxWakeSignal());

        relay.start();
        assertThat(sendStarted.await(2, TimeUnit.SECONDS)).isTrue();
        properties.setShutdownGrace(Duration.ofMillis(10));
        relay.stop();

        assertThat(store.isPublished(new MessageId("msg-interrupted"))).isFalse();
        assertThat(store.isTerminal(new MessageId("msg-interrupted"))).isFalse();
        assertThat(store.backlog().pendingCount()).isEqualTo(1);
    }

    private static OutboxRelay relay(
            InMemoryOutboxStore store,
            io.github.ande1922.moduvera.message.outbox.MessageTransport transport,
            ModuveraMessagingKafkaProperties properties) {
        var worker = new OutboxWorker(
                store,
                transport,
                CLOCK,
                properties.getClaimLease(),
                Duration.ZERO,
                3);
        return new OutboxRelay(worker, properties, new LocalOutboxWakeSignal());
    }

    private static void append(InMemoryOutboxStore store, SerializedMessage message) {
        new InMemoryDurablePublication(store).append(message);
    }

    private static ModuveraMessagingKafkaProperties properties() {
        var properties = new ModuveraMessagingKafkaProperties();
        properties.setRelayBatchSize(1);
        properties.setRelayPollInterval(Duration.ofHours(1));
        properties.setClaimLease(Duration.ofSeconds(2));
        properties.setLeaseSafetyMargin(Duration.ofMillis(10));
        properties.setBrokerAckTimeout(Duration.ofMillis(10));
        properties.setDatabaseStateUpdateBudget(Duration.ofMillis(10));
        properties.setRelayRunBudget(Duration.ofSeconds(1));
        properties.setShutdownGrace(Duration.ofSeconds(2));
        return properties;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(Duration.ofMillis(5));
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static SerializedMessage message(String id, String partitionKey) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId(id),
                        MessageKind.EVENT,
                        new MessageType("io.github.ande1922.moduvera.test.event.v1"),
                        URI.create("urn:service:test"),
                        new Destination("test.events"),
                        Instant.parse("2026-08-30T00:00:00Z"),
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "test-service"),
                        "corr-1",
                        null,
                        new Initiator(ActorType.SYSTEM, "test"),
                        "tenant-a:" + partitionKey),
                "{}");
    }
}
