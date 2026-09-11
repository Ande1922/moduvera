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
    private final PublicationLifecycle lifecycle;

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
        this(store, transport, clock, monotonicNanos, claimLease, leaseSafetyMargin, failureBackoff,
                maxAttempts, observer, PublicationLifecycle.noop());
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
            PublicationObserver observer,
            PublicationLifecycle lifecycle) {
        if (store == null
                || transport == null
                || clock == null
                || monotonicNanos == null
                || observer == null
                || lifecycle == null
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
        this.lifecycle = lifecycle;
    }

    @SuppressWarnings("PMD.CloseResource") // The resource-free placeholder is replaced once; the admitted attempt closes in finally.
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
                deferred += claimed.messages().size() - index;
                break;
            }
            ClaimedOutboxMessage entry = claimed.messages().get(index);
            long sendStarted = 0;
            boolean sendAttempted = false;
            PublicationLifecycle.Attempt attempt = PublicationLifecycle.Attempt.noop();
            try {
                try {
                    var prepared = store.preparePublication(entry, claimed.claimToken());
                    if (prepared.isEmpty()) {
                        deferred++;
                        continue;
                    }
                    entry = prepared.orElseThrow();
                    if (elapsedSince(claimStarted) >= sendStartBudgetNanos) {
                        deferred += claimed.messages().size() - index;
                        break;
                    }
                    attempt = SafePublicationAttempt.open(lifecycle, entry, maxAttempts);
                    // Keep the existing observer's send-plus-writeback duration, excluding preparation.
                    sendStarted = monotonicNanos.getAsLong();
                    sendAttempted = true;
                    try {
                        transport.send(entry.message());
                    } catch (RuntimeException | Error failure) {
                        attempt.transportCompleted(failure);
                        throw failure;
                    }
                    attempt.transportCompleted(null);
                } catch (RuntimeException failure) {
                    if (wasInterrupted(failure)) {
                        attempt.stopped(failure, true);
                        Thread.currentThread().interrupt();
                        deferred += claimed.messages().size() - index;
                        break;
                    }
                    attempt.stateStarted();
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
                    attempt.stateCompleted(result, updated);
                    if (!updated) {
                        staleUpdates++;
                        observer.staleToken(result.name().toLowerCase(java.util.Locale.ROOT));
                    }
                    if (sendAttempted) {
                        observer.completed(entry.message(), result, elapsedDuration(sendStarted));
                    }
                    failed++;
                    continue;
                }
                attempt.stateStarted();
                boolean updated = store.markPublished(
                        entry.message().descriptor().id(), claimed.claimToken(), clock.instant());
                attempt.stateCompleted(PublicationObserver.Result.PUBLISHED, updated);
                if (!updated) {
                    staleUpdates++;
                    observer.staleToken("published");
                }
                observer.completed(
                        entry.message(), PublicationObserver.Result.PUBLISHED, elapsedDuration(sendStarted));
                published++;
            } catch (RuntimeException | Error failure) {
                attempt.stopped(failure, false);
                throw failure;
            } finally {
                attempt.close();
            }
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
