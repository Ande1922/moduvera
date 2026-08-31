package io.github.ande1922.moduvera.message.outbox;

import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import java.time.Clock;
import java.time.Duration;
import java.util.function.LongSupplier;

public final class OutboxWorker {

    private final OutboxStore store;
    private final MessageTransport transport;
    private final Clock clock;
    private final LongSupplier monotonicNanos;
    private final Duration claimLease;
    private final long sendStartBudgetNanos;
    private final Duration failureBackoff;
    private final int maxAttempts;
    private final PublicationObserver observer;

    public OutboxWorker(
            OutboxStore store,
            MessageTransport transport,
            Clock clock,
            Duration claimLease,
            Duration failureBackoff,
            int maxAttempts) {
        this(
                store,
                transport,
                clock,
                System::nanoTime,
                claimLease,
                Duration.ofSeconds(1),
                failureBackoff,
                maxAttempts,
                PublicationObserver.noop());
    }

    public OutboxWorker(
            OutboxStore store,
            MessageTransport transport,
            Clock clock,
            LongSupplier monotonicNanos,
            Duration claimLease,
            Duration leaseSafetyMargin,
            Duration failureBackoff,
            int maxAttempts,
            PublicationObserver observer) {
        if (store == null
                || transport == null
                || clock == null
                || monotonicNanos == null
                || observer == null
                || maxAttempts < 1
                || claimLease == null
                || claimLease.isZero()
                || claimLease.isNegative()
                || leaseSafetyMargin == null
                || leaseSafetyMargin.isNegative()
                || leaseSafetyMargin.compareTo(claimLease) >= 0
                || failureBackoff == null
                || failureBackoff.isNegative()) {
            throw new IllegalArgumentException("outbox worker configuration is invalid");
        }
        this.store = store;
        this.transport = transport;
        this.clock = clock;
        this.monotonicNanos = monotonicNanos;
        this.claimLease = claimLease;
        this.sendStartBudgetNanos = claimLease.minus(leaseSafetyMargin).toNanos();
        this.failureBackoff = failureBackoff;
        this.maxAttempts = maxAttempts;
        this.observer = observer;
    }

    public OutboxPublishReport publishBatch(int limit) {
        long claimStarted = monotonicNanos.getAsLong();
        var batch = store.claim(limit, claimLease);
        if (batch.isEmpty()) {
            observer.claimed(0);
            if (store.claimContentionObserved()) {
                observer.claimConflict();
            }
            return new OutboxPublishReport(0, 0, 0, 0, 0);
        }
        ClaimedOutboxBatch claimed = batch.orElseThrow();
        observer.claimed(claimed.messages().size());
        int published = 0;
        int failed = 0;
        int deferred = 0;
        int staleUpdates = 0;
        for (int index = 0; index < claimed.messages().size(); index++) {
            if (elapsedSince(claimStarted) >= sendStartBudgetNanos) {
                deferred = claimed.messages().size() - index;
                break;
            }
            ClaimedOutboxMessage entry = claimed.messages().get(index);
            long sendStarted = monotonicNanos.getAsLong();
            try {
                transport.send(entry.message());
            } catch (RuntimeException failure) {
                if (wasInterrupted(failure)) {
                    Thread.currentThread().interrupt();
                    deferred = claimed.messages().size() - index;
                    break;
                }
                PublicationObserver.Result result;
                boolean updated;
                if (failure instanceof NonRetryableMessageException
                        || entry.failedAttempts() + 1 >= maxAttempts) {
                    result = PublicationObserver.Result.TERMINAL;
                    updated = store.markTerminal(
                            entry.message().descriptor().id(),
                            claimed.claimToken(),
                            clock.instant(),
                            failure.getClass().getSimpleName());
                } else {
                    result = PublicationObserver.Result.RETRY;
                    updated = store.markFailed(
                            entry.message().descriptor().id(),
                            claimed.claimToken(),
                            failureBackoff,
                            failure.getClass().getSimpleName());
                }
                if (!updated) {
                    staleUpdates++;
                    observer.staleToken(result.name().toLowerCase(java.util.Locale.ROOT));
                }
                observer.completed(entry.message(), result, elapsedDuration(sendStarted));
                failed++;
                continue;
            }
            boolean updated = store.markPublished(
                    entry.message().descriptor().id(), claimed.claimToken(), clock.instant());
            if (!updated) {
                staleUpdates++;
                observer.staleToken("published");
            }
            observer.completed(
                    entry.message(), PublicationObserver.Result.PUBLISHED, elapsedDuration(sendStarted));
            published++;
        }
        return new OutboxPublishReport(
                claimed.messages().size(), published, failed, deferred, staleUpdates);
    }

    private long elapsedSince(long started) {
        return Math.max(0L, monotonicNanos.getAsLong() - started);
    }

    private Duration elapsedDuration(long started) {
        return Duration.ofNanos(elapsedSince(started));
    }

    private static boolean wasInterrupted(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }
}
