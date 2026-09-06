package io.github.ande1922.moduvera.message;

import java.util.Objects;

/** Pure propagation values for a message's immutable creation trace relationship. */
public record TraceContextCarrier(String traceParent, String traceState) {

    public static final int MAX_TRACE_PARENT_LENGTH = 512;
    public static final int MAX_TRACE_STATE_LENGTH = 512;

    public TraceContextCarrier {
        Objects.requireNonNull(traceParent, "traceParent");
        if (traceParent.isBlank() || traceParent.length() > MAX_TRACE_PARENT_LENGTH) {
            throw new IllegalArgumentException("traceParent must be 1-512 characters");
        }
        if (traceState != null
                && (traceState.isBlank() || traceState.length() > MAX_TRACE_STATE_LENGTH)) {
            throw new IllegalArgumentException("traceState must be absent or 1-512 characters");
        }
    }
}
