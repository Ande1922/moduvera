package io.github.ande1922.moduvera.data.spring;

import io.github.ande1922.moduvera.data.NestedTransactionBoundaryException;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class SpringTransactionBoundary implements TransactionBoundary {

    private final TransactionOperations transactionOperations;

    public SpringTransactionBoundary(TransactionOperations transactionOperations) {
        this.transactionOperations = transactionOperations;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new NestedTransactionBoundaryException();
        }
        return transactionOperations.execute(status -> work.get());
    }
}
