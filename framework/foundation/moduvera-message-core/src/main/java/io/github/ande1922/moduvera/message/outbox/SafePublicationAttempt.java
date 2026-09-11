package io.github.ande1922.moduvera.message.outbox;

/** Optional diagnostic failures cannot become transport failures or replace a completion-write error. */
final class SafePublicationAttempt implements PublicationLifecycle.Attempt {
    private final PublicationLifecycle.Attempt delegate;

    private SafePublicationAttempt(PublicationLifecycle.Attempt delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "publication attempt");
    }

    static PublicationLifecycle.Attempt open(PublicationLifecycle lifecycle, ClaimedOutboxMessage message, int failureLimit) {
        try {
            return new SafePublicationAttempt(lifecycle.open(message, failureLimit));
        } catch (RuntimeException unavailable) {
            return PublicationLifecycle.Attempt.noop();
        }
    }

    @Override public void transportCompleted(Throwable failure) {
        observe(() -> delegate.transportCompleted(failure));
    }

    @Override public void stateStarted() {
        observe(delegate::stateStarted);
    }

    @Override public void stateCompleted(PublicationObserver.Result result, boolean updated) {
        observe(() -> delegate.stateCompleted(result, updated));
    }

    @Override public void stopped(Throwable failure, boolean interrupted) {
        observe(() -> delegate.stopped(failure, interrupted));
    }

    @Override public void close() {
        observe(delegate::close);
    }

    private static void observe(Runnable diagnostic) {
        try {
            diagnostic.run();
        } catch (RuntimeException unavailable) {
            // No retry decision or second logging path for a diagnostic backend failure.
        }
    }
}
