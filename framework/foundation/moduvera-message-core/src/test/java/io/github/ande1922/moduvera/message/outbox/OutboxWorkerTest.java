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
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OutboxWorkerTest {

    private static final Clock WALL_CLOCK =
            Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void preparesBeforeTransportWithoutAddingPreparationTimeToTheExistingObserverDuration() {
        var ticker = new AtomicLong();
        var order = new ArrayList<String>();
        var store = new PreparationStore(List.of(new ClaimedOutboxMessage(message("prepared", "prepared"), 0)));
        store.prepare = entry -> {
            order.add("prepared");
            ticker.set(1_000_000);
            return Optional.of(entry);
        };
        store.onPublished = () -> {
            order.add("marked");
            ticker.set(4_000_000);
        };
        var duration = new AtomicReference<Duration>();
        var observer = new PublicationObserver() {
            @Override
            public void completed(SerializedMessage message, Result result, Duration elapsed) {
                duration.set(elapsed);
            }
        };
        var worker = preparationWorker(store, ignored -> {
            order.add("sent");
            ticker.set(3_000_000);
        }, ticker, observer, 3);

        assertThat(worker.publishBatch(1)).isEqualTo(new OutboxPublishReport(1, 1, 0, 0, 0));
        assertThat(order).containsExactly("prepared", "sent", "marked");
        assertThat(duration).hasValue(Duration.ofMillis(3));
    }

    @Test
    void defersLostClaimsAndRechecksTheLeaseAfterSlowPreparation() {
        var ticker = new AtomicLong();
        var store = new PreparationStore(List.of(
                new ClaimedOutboxMessage(message("stale", "stale"), 0),
                new ClaimedOutboxMessage(message("slow", "slow"), 0)));
        store.prepare = entry -> {
            if (entry.message().descriptor().id().value().equals("stale")) {
                return Optional.empty();
            }
            ticker.set(Duration.ofSeconds(8).toNanos());
            return Optional.of(entry);
        };
        var worker = preparationWorker(store, ignored -> {
            throw new AssertionError("a rejected or late preparation cannot send");
        }, ticker, PublicationObserver.noop(), 3);

        assertThat(worker.publishBatch(2)).isEqualTo(new OutboxPublishReport(2, 0, 0, 2, 0));
        assertThat(store.failures).isZero();
        assertThat(store.terminals).isZero();
    }

    @Test
    void preparationFailureRetainsRetryAndTerminalPolicyWithoutInventingAnAckSample() {
        for (int maximum : List.of(1, 2)) {
            var store = new PreparationStore(List.of(new ClaimedOutboxMessage(message("failed", "failed"), 0)));
            store.prepare = entry -> { throw new IllegalStateException("preparation failed"); };
            var observer = new PublicationObserver() {
                @Override
                public void completed(SerializedMessage message, Result result, Duration elapsed) {
                    throw new AssertionError("no transport attempt took place");
                }
            };
            var worker = preparationWorker(store, ignored -> {
                throw new AssertionError("failed preparation cannot send");
            }, new AtomicLong(), observer, maximum);

            assertThat(worker.publishBatch(1)).isEqualTo(new OutboxPublishReport(1, 0, 1, 0, 0));
            assertThat(store.terminals).isEqualTo(maximum == 1 ? 1 : 0);
            assertThat(store.failures).isEqualTo(maximum == 1 ? 0 : 1);
        }
    }

    @Test
    void interruptedPreparationDefersWithoutIncrementingTheFailureCount() {
        var store = new PreparationStore(List.of(new ClaimedOutboxMessage(message("interrupted", "interrupted"), 0)));
        store.prepare = entry -> { throw new IllegalStateException(new InterruptedException("cancelled")); };
        var worker = preparationWorker(store, ignored -> {
            throw new AssertionError("cancelled preparation cannot send");
        }, new AtomicLong(), PublicationObserver.noop(), 3);
        try {
            assertThat(worker.publishBatch(1)).isEqualTo(new OutboxPublishReport(1, 0, 0, 1, 0));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(store.failures).isZero();
            assertThat(store.terminals).isZero();
        } finally {
            Thread.interrupted();
        }
    }

    private static OutboxWorker preparationWorker(PreparationStore store, MessageTransport transport,
            AtomicLong ticker, PublicationObserver observer, int maximum) {
        return new OutboxWorker(store, transport, WALL_CLOCK, ticker::get, Duration.ofSeconds(10),
                Duration.ofSeconds(2), Duration.ZERO, maximum, observer);
    }

    /** Orchestration probe only; database atomicity is verified by the production JDBC fixture. */
    private static final class PreparationStore implements OutboxStore {
        private final List<ClaimedOutboxMessage> entries;
        private Function<ClaimedOutboxMessage, Optional<ClaimedOutboxMessage>> prepare = Optional::of;
        private Runnable onPublished = () -> {};
        private int failures;
        private int terminals;

        private PreparationStore(List<ClaimedOutboxMessage> entries) {
            this.entries = entries;
        }

        @Override
        public Optional<ClaimedOutboxBatch> claim(int limit, Duration lease) {
            return Optional.of(new ClaimedOutboxBatch("claim-token", entries));
        }

        @Override
        public Optional<ClaimedOutboxMessage> preparePublication(ClaimedOutboxMessage claimed, String token) {
            assertThat(token).isEqualTo("claim-token");
            return prepare.apply(claimed);
        }

        @Override
        public boolean markPublished(MessageId id, String token, Instant at) {
            onPublished.run();
            return true;
        }

        @Override
        public boolean markFailed(MessageId id, String token, Duration delay, String failure) {
            failures++;
            return true;
        }

        @Override
        public boolean markTerminal(MessageId id, String token, Instant at, String failure) {
            terminals++;
            return true;
        }
    }

    @Test
    void defersSendsThatWouldStartInsideTheConservativeLeaseMargin() {
        var first = message("msg-first", "order-first");
        var second = message("msg-second", "order-second");
        var claimed = new ClaimedOutboxBatch(
                "claim-token",
                List.of(new ClaimedOutboxMessage(first, 0), new ClaimedOutboxMessage(second, 0)));
        AtomicReference<MessageId> published = new AtomicReference<>();
        OutboxStore scripted = new OutboxStore() {
            @Override
            public Optional<ClaimedOutboxBatch> claim(int limit, Duration lease) {
                return Optional.of(claimed);
            }

            @Override
            public boolean markPublished(MessageId id, String claimToken, Instant publishedAt) {
                published.set(id);
                return true;
            }

            @Override
            public boolean markFailed(
                    MessageId id, String claimToken, Duration retryDelay, String safeFailure) {
                throw new AssertionError("failure update was not expected");
            }

            @Override
            public boolean markTerminal(
                    MessageId id, String claimToken, Instant terminalAt, String safeFailure) {
                throw new AssertionError("terminal update was not expected");
            }
        };
        AtomicLong ticker = new AtomicLong();
        AtomicInteger sends = new AtomicInteger();
        var worker = new OutboxWorker(
                scripted,
                ignored -> {
                    sends.incrementAndGet();
                    ticker.set(Duration.ofSeconds(9).toNanos());
                },
                WALL_CLOCK,
                ticker::get,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                Duration.ZERO,
                3,
                PublicationObserver.noop());

        assertThat(worker.publishBatch(10))
                .isEqualTo(new OutboxPublishReport(2, 1, 0, 1, 0));
        assertThat(sends).hasValue(1);
        assertThat(published).hasValue(first.descriptor().id());
    }

    @Test
    void terminalsTheRetryWhoseNextFailureExhaustsTheAttemptBudget() {
        var exhausted = message("msg-exhausted", "order-exhausted");
        var claimed = new ClaimedOutboxBatch(
                "claim-token", List.of(new ClaimedOutboxMessage(exhausted, 1)));
        AtomicReference<MessageId> terminal = new AtomicReference<>();
        AtomicReference<String> safeFailure = new AtomicReference<>();
        OutboxStore scripted = new OutboxStore() {
            @Override
            public Optional<ClaimedOutboxBatch> claim(int limit, Duration lease) {
                return Optional.of(claimed);
            }

            @Override
            public boolean markPublished(MessageId id, String claimToken, Instant publishedAt) {
                throw new AssertionError("published update was not expected");
            }

            @Override
            public boolean markFailed(
                    MessageId id, String claimToken, Duration retryDelay, String failure) {
                throw new AssertionError("retry update was not expected");
            }

            @Override
            public boolean markTerminal(
                    MessageId id, String claimToken, Instant terminalAt, String failure) {
                terminal.set(id);
                safeFailure.set(failure);
                return true;
            }
        };
        var worker = new OutboxWorker(
                scripted,
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

        assertThat(worker.publishBatch(1))
                .isEqualTo(new OutboxPublishReport(1, 0, 1, 0, 0));
        assertThat(terminal).hasValue(exhausted.descriptor().id());
        assertThat(safeFailure).hasValue("IllegalStateException");
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

    private static SerializedMessage message(String id, String partitionKey) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId(id),
                        MessageKind.ASYNC_COMMAND,
                        new MessageType(
                                "io.github.ande1922.moduvera.reference.inventory.reserve.v1"),
                        URI.create("urn:service:order"),
                        new Destination("inventory.commands"),
                        Instant.parse("2026-08-30T00:00:00Z"),
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "order-service"),
                        "corr-1",
                        null,
                        new Initiator(ActorType.USER, "alice"),
                        "tenant-a:" + partitionKey),
                "{}");
    }
}
