package io.github.ande1922.moduvera.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TransactionBoundaryTest {

    @Test
    void runnableOverloadUsesTheSameBoundary() {
        AtomicBoolean called = new AtomicBoolean();
        TransactionBoundary boundary = new TransactionBoundary() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                return work.get();
            }
        };

        boundary.inTransaction(() -> called.set(true));

        assertThat(called).isTrue();
    }
}
