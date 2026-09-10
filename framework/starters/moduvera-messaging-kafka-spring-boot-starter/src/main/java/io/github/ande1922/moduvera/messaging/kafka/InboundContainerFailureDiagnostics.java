package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.logging.DiagnosticLogSnapshot;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Diagnostic-only metadata for the original Error, never a wrapper or an authorization scope. */
final class InboundContainerFailureDiagnostics {
    private static final String CONTAINER_LOGGER = "org.springframework.kafka.listener.KafkaMessageListenerContainer";
    private static final ReferenceQueue<Error> EXPIRED = new ReferenceQueue<>();
    private static final Map<ErrorKey, Map<Long, Failure>> FAILURES = new HashMap<>();
    private static int installedFilters;

    private InboundContainerFailureDiagnostics() {}

    static synchronized void install() {
        installedFilters++;
    }

    static synchronized void uninstall() {
        if (--installedFilters == 0) {
            FAILURES.clear();
            drainExpired();
        }
    }

    static synchronized void retain(Error error) {
        // Allocating diagnostic data cannot be promised after VM resource exhaustion.
        if (installedFilters == 0 || error instanceof VirtualMachineError) {
            return;
        }
        drainExpired();
        var snapshot = DiagnosticLogSnapshot.capture(ExecutionContextHolder.require().correlationId());
        FAILURES.computeIfAbsent(new ErrorKey(error, EXPIRED), ignored -> new LinkedHashMap<>())
                .put(Thread.currentThread().threadId(), new Failure(snapshot));
    }

    static synchronized Decision nativeRecord(String logger, String message, Throwable throwable) {
        if (!CONTAINER_LOGGER.equals(logger) || throwable == null
                || !("Stopping container due to an Error".equals(message)
                        || "Error while stopping the container".equals(message))) {
            return null;
        }
        drainExpired();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = throwable; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof Error error) {
                Decision decision = match(error, message);
                if (decision != null) {
                    return decision;
                }
            }
        }
        return null;
    }

    private static Decision match(Error error, String message) {
        var key = new ErrorKey(error, null);
        Map<Long, Failure> threads = FAILURES.get(key);
        if (threads == null) {
            return null;
        }
        long threadId = Thread.currentThread().threadId();
        Failure failure = threads.get(threadId);
        if ("Stopping container due to an Error".equals(message)) {
            // If an application reuses one Error concurrently, only its own consumer thread's
            // snapshot can enrich the first native record; never borrow another delivery.
            if (failure == null || failure.reported) {
                return null;
            }
            failure.reported = true;
            return new Decision(failure.snapshot);
        }
        if (failure == null) {
            // A completion callback may run on a different thread. Suppress only when every
            // candidate's original Error record was already emitted with its own identity.
            if (threads.values().stream().anyMatch(candidate -> !candidate.reported)) {
                return null;
            }
            threadId = threads.keySet().iterator().next();
            failure = threads.get(threadId);
        }
        if (!failure.reported) {
            return null;
        }
        threads.remove(threadId);
        if (threads.isEmpty()) {
            FAILURES.remove(key);
        }
        return new Decision(null);
    }

    private static void drainExpired() {
        for (var reference = EXPIRED.poll(); reference != null; reference = EXPIRED.poll()) {
            FAILURES.remove(reference);
        }
    }

    record Decision(DiagnosticLogSnapshot snapshot) {}

    private static final class Failure {
        private final DiagnosticLogSnapshot snapshot;
        private boolean reported;

        private Failure(DiagnosticLogSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    private static final class ErrorKey extends WeakReference<Error> {
        private final int identityHash;

        private ErrorKey(Error error, ReferenceQueue<Error> queue) {
            super(error, queue);
            identityHash = System.identityHashCode(error);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            Error error = get();
            return this == other || error != null && other instanceof ErrorKey key && error == key.get();
        }
    }
}
