package io.github.ande1922.moduvera.testing;

import java.time.Duration;

public final class EventuallyTimeoutException extends AssertionError {

    public EventuallyTimeoutException(Duration timeout, String diagnostics) {
        super("condition did not become true within " + timeout + "; diagnostics: " + diagnostics);
    }
}
