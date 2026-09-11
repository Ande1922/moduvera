package io.github.ande1922.moduvera.message.outbox;

/**
 * Runtime diagnostic scope for an admitted send and its completion write.
 * Implementations must not change retry decisions or install authorization state.
 * The separate PublicationObserver retains its existing metrics duration contract.
 */
public interface PublicationLifecycle {

    /** Opening failures must release any partially installed scopes before escaping. */
    Attempt open(ClaimedOutboxMessage message, int failureLimit);

    static PublicationLifecycle noop() {
        return (message, failureLimit) -> Attempt.noop();
    }

    interface Attempt extends AutoCloseable {
        static Attempt noop() {
            return new Attempt() {};
        }

        /** Null means the synchronous transport call returned successfully. */
        default void transportCompleted(Throwable failure) {}

        default void stateStarted() {}

        default void stateCompleted(PublicationObserver.Result result, boolean updated) {}

        /** An exception escaped the existing send/write policy, or interruption deferred this batch. */
        default void stopped(Throwable failure, boolean interrupted) {}

        @Override
        default void close() {}
    }
}
