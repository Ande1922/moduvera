package io.github.ande1922.moduvera.data.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.data.NestedTransactionBoundaryException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class SpringTransactionBoundaryTest {

    @Test
    void createsOneExplicitReadCommittedTransaction() {
        TransactionTemplate template = new TransactionTemplate(new StubTransactionManager());
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        SpringTransactionBoundary boundary = new SpringTransactionBoundary(template);

        String value = boundary.inTransaction(() -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThatThrownBy(() -> boundary.inTransaction(() -> "nested"))
                    .isInstanceOf(NestedTransactionBoundaryException.class);
            return "committed";
        });

        assertThat(value).isEqualTo("committed");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private static final class StubTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
