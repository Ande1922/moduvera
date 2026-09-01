package io.github.ande1922.moduvera.data;

import java.util.function.Supplier;

/** Defines one explicit top-level local database transaction. */
public interface TransactionBoundary {

    <T> T inTransaction(Supplier<T> work);

    default void inTransaction(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }
}
