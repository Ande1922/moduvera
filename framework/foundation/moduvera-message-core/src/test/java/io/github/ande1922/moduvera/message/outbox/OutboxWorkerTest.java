package io.github.ande1922.moduvera.message.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.message.MessageId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OutboxWorkerTest {

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
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC),
                new AtomicLong()::get,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ZERO,
                3,
                observer);

        assertThat(worker.publishBatch(1).claimed()).isZero();
        assertThat(conflicts).hasValue(1);
    }
}
