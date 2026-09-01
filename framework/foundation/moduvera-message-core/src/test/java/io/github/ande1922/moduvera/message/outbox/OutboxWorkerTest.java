package io.github.ande1922.moduvera.message.outbox;

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
import io.github.ande1922.moduvera.message.outbox.memory.InMemoryDurablePublication;
import io.github.ande1922.moduvera.message.outbox.memory.InMemoryOutboxStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OutboxWorkerTest {

    private static final Clock WALL_CLOCK =
            Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void retriesAStoredIntentUntilTheBrokerAcknowledgesIt() {
        var store = new InMemoryOutboxStore(WALL_CLOCK);
        var original = message("msg-1", "order-1");
        append(store, original);
        AtomicInteger sends = new AtomicInteger();
        MessageTransport transport = ignored -> {
            if (sends.incrementAndGet() == 1) {
                throw new IllegalStateException("broker unavailable");
            }
        };
        var worker = worker(store, transport, new AtomicLong());

        assertThat(worker.publishBatch(10)).isEqualTo(new OutboxPublishReport(1, 0, 1, 0, 0));
        assertThat(worker.publishBatch(10)).isEqualTo(new OutboxPublishReport(1, 1, 0, 0, 0));
        assertThat(store.isPublished(original.descriptor().id())).isTrue();
        assertThat(sends).hasValue(2);
    }

    @Test
    void countsOnlyCompletedSendFailuresAsAttempts() {
        var store = new InMemoryOutboxStore(WALL_CLOCK);
        append(store, message("msg-attempt", "order-1"));

        var abandoned = store.claim(1, Duration.ofSeconds(30)).orElseThrow();
        assertThat(abandoned.messages().getFirst().failedAttempts()).isZero();
        assertThat(store.markFailed(
                        abandoned.messages().getFirst().message().descriptor().id(),
                        abandoned.claimToken(),
                        Duration.ZERO,
                        "Timeout"))
                .isTrue();

        var retry = store.claim(1, Duration.ofSeconds(30)).orElseThrow();
        assertThat(retry.messages().getFirst().failedAttempts()).isEqualTo(1);
    }

    @Test
    void givesEveryMessageInOneAtomicClaimTheSameBatchToken() {
        var store = new InMemoryOutboxStore(WALL_CLOCK);
        append(store, message("msg-a", "order-a"));
        append(store, message("msg-b", "order-b"));

        var batch = store.claim(10, Duration.ofSeconds(30)).orElseThrow();

        assertThat(batch.messages()).hasSize(2);
        assertThat(batch.claimToken()).isNotBlank();
        assertThat(batch.messages()).allMatch(entry -> entry.failedAttempts() == 0);
    }

    @Test
    void doesNotStartRemainingSendsAfterTheConservativeLeaseDeadline() {
        var store = new InMemoryOutboxStore(WALL_CLOCK);
        append(store, message("msg-a", "order-a"));
        append(store, message("msg-b", "order-b"));
        AtomicLong ticker = new AtomicLong();
        AtomicInteger sends = new AtomicInteger();
        MessageTransport transport = ignored -> {
            sends.incrementAndGet();
            ticker.set(Duration.ofSeconds(9).toNanos());
        };
        var worker = new OutboxWorker(
                store,
                transport,
                WALL_CLOCK,
                ticker::get,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                Duration.ZERO,
                3,
                PublicationObserver.noop());

        assertThat(worker.publishBatch(10)).isEqualTo(new OutboxPublishReport(2, 1, 0, 1, 0));
        assertThat(sends).hasValue(1);
        assertThat(store.isPublished(new MessageId("msg-a"))).isTrue();
        assertThat(store.isPublished(new MessageId("msg-b"))).isFalse();
    }

    @Test
    void allowsTheInFlightAmbiguityWindowButEventuallyPublishesWithOneValidToken() {
        var mutableClock = new MutableClock(Instant.parse("2026-08-30T00:00:00Z"));
        var store = new InMemoryOutboxStore(mutableClock);
        append(store, message("msg-ambiguous", "order-a"));
        AtomicInteger sends = new AtomicInteger();
        var takeover = new OutboxWorker(
                store,
                ignored -> sends.incrementAndGet(),
                mutableClock,
                new AtomicLong()::get,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ZERO,
                3,
                PublicationObserver.noop());
        var original = new OutboxWorker(
                store,
                ignored -> {
                    sends.incrementAndGet();
                    mutableClock.advance(Duration.ofSeconds(31));
                    assertThat(takeover.publishBatch(1).published()).isEqualTo(1);
                },
                mutableClock,
                new AtomicLong()::get,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ZERO,
                3,
                PublicationObserver.noop());

        var originalReport = original.publishBatch(1);

        assertThat(originalReport.staleUpdates()).isEqualTo(1);
        assertThat(sends).hasValue(2);
        assertThat(store.isPublished(new MessageId("msg-ambiguous"))).isTrue();
    }

    @Test
    void interruptionLeavesTheUnknownInFlightSendClaimedWithoutConsumingAnAttempt() {
        var mutableClock = new MutableClock(Instant.parse("2026-08-30T00:00:00Z"));
        var store = new InMemoryOutboxStore(mutableClock);
        append(store, message("msg-interrupted", "order-a"));
        var worker = new OutboxWorker(
                store,
                ignored -> {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(new InterruptedException("forced stop"));
                },
                mutableClock,
                new AtomicLong()::get,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ZERO,
                3,
                PublicationObserver.noop());

        try {
            assertThat(worker.publishBatch(1))
                    .isEqualTo(new OutboxPublishReport(1, 0, 0, 1, 0));
        } finally {
            Thread.interrupted();
        }
        mutableClock.advance(Duration.ofSeconds(31));

        var reclaimed = store.claim(1, Duration.ofSeconds(30)).orElseThrow();
        assertThat(reclaimed.messages().getFirst().failedAttempts()).isZero();
    }

    @Test
    void observesClaimContentionIndependentlyFromStaleFencingUpdates() {
        AtomicInteger conflicts = new AtomicInteger();
        OutboxStore contended = new OutboxStore() {
            @Override
            public Optional<ClaimedOutboxBatch> claim(int limit, Duration lease) {
                return Optional.empty();
            }

            @Override
            public boolean claimContentionObserved() {
                return true;
            }

            @Override
            public boolean markPublished(MessageId id, String claimToken, Instant publishedAt) {
                return false;
            }

            @Override
            public boolean markFailed(
                    MessageId id, String claimToken, Duration retryDelay, String safeFailure) {
                return false;
            }

            @Override
            public boolean markTerminal(
                    MessageId id, String claimToken, Instant terminalAt, String safeFailure) {
                return false;
            }
        };
        PublicationObserver observer = new PublicationObserver() {
            @Override
            public void claimConflict() {
                conflicts.incrementAndGet();
            }
        };
        var worker = new OutboxWorker(
                contended,
                ignored -> {},
                WALL_CLOCK,
                new AtomicLong()::get,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ZERO,
                3,
                observer);

        assertThat(worker.publishBatch(1).claimed()).isZero();
        assertThat(conflicts).hasValue(1);
    }

    @Test
    void terminalsNonRetryableAndExhaustedMessagesInsteadOfRetryingForever() {
        var nonRetryableStore = new InMemoryOutboxStore(WALL_CLOCK);
        append(nonRetryableStore, message("msg-terminal", "order-1"));
        var nonRetryableWorker = worker(
                nonRetryableStore,
                ignored -> {
                    throw new io.github.ande1922.moduvera.message.NonRetryableMessageException("invalid route");
                },
                new AtomicLong());
        assertThat(nonRetryableWorker.publishBatch(1).failed()).isEqualTo(1);
        assertThat(nonRetryableStore.isTerminal(new MessageId("msg-terminal"))).isTrue();
        assertThat(nonRetryableWorker.publishBatch(1).claimed()).isZero();

        var exhaustedStore = new InMemoryOutboxStore(WALL_CLOCK);
        append(exhaustedStore, message("msg-exhausted", "order-2"));
        var exhaustedWorker = new OutboxWorker(
                exhaustedStore,
                ignored -> {
                    throw new IllegalStateException("broker unavailable");
                },
                WALL_CLOCK,
                new AtomicLong()::get,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ZERO,
                2,
                PublicationObserver.noop());
        assertThat(exhaustedWorker.publishBatch(1).failed()).isEqualTo(1);
        assertThat(exhaustedWorker.publishBatch(1).failed()).isEqualTo(1);
        assertThat(exhaustedStore.isTerminal(new MessageId("msg-exhausted"))).isTrue();
    }

    private static OutboxWorker worker(
            InMemoryOutboxStore store, MessageTransport transport, AtomicLong ticker) {
        return new OutboxWorker(
                store,
                transport,
                WALL_CLOCK,
                ticker::get,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ZERO,
                3,
                PublicationObserver.noop());
    }

    private static void append(InMemoryOutboxStore store, SerializedMessage message) {
        new InMemoryDurablePublication(store).append(message);
    }

    private static SerializedMessage message(String id, String partitionKey) {
        var descriptor = new MessageDescriptor(
                new MessageId(id),
                MessageKind.ASYNC_COMMAND,
                new MessageType("io.github.ande1922.moduvera.reference.inventory.reserve.v1"),
                URI.create("urn:service:order"),
                new Destination("inventory.commands"),
                Instant.parse("2026-08-30T00:00:00Z"),
                new TenantId("tenant-a"),
                new Actor(ActorType.SERVICE, "order-service"),
                "corr-1",
                null,
                new Initiator(ActorType.USER, "alice"),
                "tenant-a:" + partitionKey);
        return SerializedMessage.json(descriptor, "{}");
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
