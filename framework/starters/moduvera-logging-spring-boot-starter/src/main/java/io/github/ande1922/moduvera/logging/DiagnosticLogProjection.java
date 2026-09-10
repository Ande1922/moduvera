package io.github.ande1922.moduvera.logging;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Thread-owned fallback for synchronous log formatting inside a transport invocation.
 * Reads the latest immutable trusted snapshot without installing authorization or OTel state.
 * Never retain this scope across asynchronous waits or pass its accessor to a deferred appender.
 */
public final class DiagnosticLogProjection implements AutoCloseable {
    private static final ThreadLocal<DiagnosticLogProjection> CURRENT = new ThreadLocal<>();
    private final Thread owner = Thread.currentThread();
    private final Supplier<DiagnosticLogSnapshot> snapshot;
    private final DiagnosticLogProjection previous;
    private boolean closed;

    private DiagnosticLogProjection(Supplier<DiagnosticLogSnapshot> snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        previous = CURRENT.get();
    }

    /** The accessor may return null before the transport establishes its diagnostic state. */
    public static DiagnosticLogProjection open(Supplier<DiagnosticLogSnapshot> snapshot) {
        var scope = new DiagnosticLogProjection(snapshot);
        CURRENT.set(scope);
        return scope;
    }

    // This is a borrowed scope: only the transport invocation that opened it may close it.
    @SuppressWarnings("PMD.CloseResource")
    static Map<String, String> currentFields() {
        var scope = CURRENT.get();
        var value = scope == null ? null : scope.snapshot.get();
        return value == null ? Map.of() : value.fields();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (Thread.currentThread() != owner || CURRENT.get() != this) {
            throw new IllegalStateException("diagnostic projections must close in order on their owning thread");
        }
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
        closed = true;
    }
}
