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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OutboxWorkerTest {

    private static final Clock WALL_CLOCK =
            Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC);

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
