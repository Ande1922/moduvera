package io.github.ande1922.moduvera.message.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void admittedLifecycleCoversSendAndWriteButDoesNotChangeObserverDuration() {
        var ticker = new AtomicLong();
        var order = new ArrayList<String>();
        var original = new ClaimedOutboxMessage(message("lifecycle", "lifecycle"), 0);
        var prepared = new ClaimedOutboxMessage(original.message(), 2, 4, "stored-parent", "stored-state");
        var store = new PreparationStore(List.of(original));
        store.prepare = ignored -> {
            ticker.set(100_000_000);
            return Optional.of(prepared);
        };
        store.onPublished = () -> { order.add("write"); ticker.addAndGet(3_000_000); };
        PublicationLifecycle lifecycle = (entry, limit) -> {
            assertThat(entry).isSameAs(prepared);
            assertThat(limit).isEqualTo(5);
            order.add("open");
            ticker.addAndGet(7_000_000);
            return new PublicationLifecycle.Attempt() {
                @Override
                public void transportCompleted(Throwable failure) {
                    assertThat(failure).isNull();
                    order.add("ack");
                }

                @Override
                public void stateCompleted(PublicationObserver.Result result, boolean updated) {
                    assertThat(result).isEqualTo(PublicationObserver.Result.PUBLISHED);
                    assertThat(updated).isTrue();
                    order.add("state");
                }

                @Override
                public void close() {
                    order.add("close");
                    ticker.addAndGet(9_000_000);
                }
            };
        };
        var duration = new AtomicReference<Duration>();
        var observer = new PublicationObserver() {
            @Override
            public void completed(SerializedMessage message, Result result, Duration elapsed) {
                order.add("observed");
                duration.set(elapsed);
            }
        };
        var worker = new OutboxWorker(store, ignored -> { order.add("send"); ticker.addAndGet(2_000_000); },
                WALL_CLOCK, ticker::get, Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ZERO,
                5, observer, lifecycle);
        assertThat(worker.publishBatch(1)).isEqualTo(new OutboxPublishReport(1, 1, 0, 0, 0));
        assertThat(order).containsExactly("open", "send", "ack", "write", "state", "observed", "close");
        assertThat(duration).hasValue(Duration.ofMillis(5));
    }

    @Test
    void successfulSendWithFailedWriteEscapesWithoutRetryCountingAndClosesScope() {
        var store = new PreparationStore(List.of(new ClaimedOutboxMessage(message("write-fails", "write-fails"), 0)));
        var writeFailure = new IllegalStateException("write failed");
        store.onPublished = () -> { throw writeFailure; };
        var order = new ArrayList<String>();
        var worker = lifecycleWorker(store, ignored -> {}, new PublicationLifecycle.Attempt() {
            @Override
            public void transportCompleted(Throwable failure) {
                assertThat(failure).isNull();
                order.add("ack");
            }

            @Override
            public void stateCompleted(PublicationObserver.Result result, boolean updated) {
                throw new AssertionError("a failed write has no accepted disposition");
            }

            @Override
            public void stopped(Throwable failure, boolean interrupted) {
                assertThat(failure).isSameAs(writeFailure);
                assertThat(interrupted).isFalse();
                order.add("escaped");
            }

            @Override
            public void close() { order.add("close"); }
        }, 3);
        assertThatThrownBy(() -> worker.publishBatch(1)).isSameAs(writeFailure);
        assertThat(order).containsExactly("ack", "escaped", "close");
        assertThat(store.failures).isZero();
        assertThat(store.terminals).isZero();
    }

    @Test
    void failedSendLifecycleReportsTheWorkersDispositionAndInterruptionWithoutChangingPolicy() {
        for (int maximum : List.of(1, 2, 3)) {
            var store = new PreparationStore(List.of(new ClaimedOutboxMessage(message("stop", "stop"), 0)));
            var failure = maximum == 3 ? new IllegalStateException(new InterruptedException())
                    : new IllegalStateException("send failed");
            var order = new ArrayList<String>();
            var worker = lifecycleWorker(store, ignored -> { throw failure; }, new PublicationLifecycle.Attempt() {
                @Override
                public void transportCompleted(Throwable actual) {
                    assertThat(actual).isSameAs(failure);
                    order.add("failed");
                }

                @Override
                public void stateCompleted(PublicationObserver.Result result, boolean updated) {
                    assertThat(updated).isTrue();
                    order.add(result.name());
                }

                @Override
                public void stopped(Throwable actual, boolean interrupted) {
                    assertThat(actual).isSameAs(failure);
                    assertThat(interrupted).isTrue();
                    order.add("interrupted");
                }

                @Override
                public void close() { order.add("close"); }
            }, maximum);
            try {
                var report = worker.publishBatch(1);
                assertThat(order).containsExactly("failed", maximum == 1 ? "TERMINAL"
                        : maximum == 2 ? "RETRY" : "interrupted", "close");
                assertThat(report.failed()).isEqualTo(maximum == 3 ? 0 : 1);
                assertThat(report.deferred()).isEqualTo(maximum == 3 ? 1 : 0);
                assertThat(store.failures + store.terminals).isEqualTo(maximum == 3 ? 0 : 1);
                assertThat(Thread.currentThread().isInterrupted()).isEqualTo(maximum == 3);
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void diagnosticBackendFailureCannotCreateARetryOrPreventSuccessfulCompletion() {
        for (boolean failOpening : List.of(false, true)) {
            var store = new PreparationStore(List.of(new ClaimedOutboxMessage(message("diagnostic", "diagnostic"), 0)));
            var sends = new AtomicInteger();
            var closed = new AtomicInteger();
            PublicationLifecycle lifecycle = (entry, limit) -> {
                if (failOpening) { throw new IllegalStateException("diagnostic unavailable"); }
                return new PublicationLifecycle.Attempt() {
                    @Override public void transportCompleted(Throwable failure) { throw new IllegalStateException("diagnostic unavailable"); }
                    @Override public void stateCompleted(PublicationObserver.Result result, boolean updated) { throw new IllegalStateException("diagnostic unavailable"); }
                    @Override public void close() { closed.incrementAndGet(); throw new IllegalStateException("diagnostic unavailable"); }
                };
            };
            var worker = new OutboxWorker(store, ignored -> sends.incrementAndGet(), WALL_CLOCK, new AtomicLong()::get,
                    Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ZERO, 3, PublicationObserver.noop(), lifecycle);
            assertThat(worker.publishBatch(1)).isEqualTo(new OutboxPublishReport(1, 1, 0, 0, 0));
            assertThat(sends).hasValue(1);
            assertThat(store.failures + store.terminals).isZero();
            assertThat(closed).hasValue(failOpening ? 0 : 1);
        }
    }

    @Test
    void diagnosticFailureCannotReplaceTheOriginalWriteFailure() {
        var store = new PreparationStore(List.of(new ClaimedOutboxMessage(message("write-diagnostic", "write-diagnostic"), 0)));
        var original = new IllegalStateException("database write failed");
        store.onPublished = () -> { throw original; };
        var worker = lifecycleWorker(store, ignored -> {}, new PublicationLifecycle.Attempt() {
            @Override public void stopped(Throwable failure, boolean interrupted) { throw new IllegalStateException("diagnostic unavailable"); }
            @Override public void close() { throw new IllegalStateException("diagnostic unavailable"); }
        }, 3);
        assertThatThrownBy(() -> worker.publishBatch(1)).isSameAs(original);
        assertThat(store.failures + store.terminals).isZero();
    }

    private static OutboxWorker lifecycleWorker(PreparationStore store, MessageTransport transport,
            PublicationLifecycle.Attempt attempt, int maximum) {
        return new OutboxWorker(store, transport, WALL_CLOCK, new AtomicLong()::get, Duration.ofSeconds(10),
                Duration.ofSeconds(2), Duration.ZERO, maximum, PublicationObserver.noop(), (entry, limit) -> attempt);
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
