package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.publication.DurablePublication;
import io.github.ande1922.moduvera.message.publication.DurablePublicationTransactionException;
import java.sql.Connection;
import java.util.Objects;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcDurablePublication implements DurablePublication {

    private final DataSource dataSource;
    private final Consumer<SerializedMessage> appendIntent;

    public JdbcDurablePublication(DataSource dataSource, JdbcOutboxStore store) {
        this(dataSource, store::appendIntent);
    }

    JdbcDurablePublication(DataSource dataSource, Consumer<SerializedMessage> appendIntent) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.appendIntent = Objects.requireNonNull(appendIntent, "appendIntent");
    }

    @Override
    @SuppressWarnings("PMD.CloseResource") // DataSourceUtils releases or retains the transaction-bound connection.
    public void append(SerializedMessage message) {
        Objects.requireNonNull(message, "message");
        requireWritableTransaction();
        if (!TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new DurablePublicationTransactionException(
                    "DurablePublication requires a transaction bound to its outbox DataSource");
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            if (!DataSourceUtils.isConnectionTransactional(connection, dataSource)) {
                throw new DurablePublicationTransactionException(
                        "DurablePublication requires a transaction bound to its outbox DataSource");
            }
            appendIntent.accept(message);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new DurablePublicationTransactionException(
                    "DurablePublication requires an active local database transaction");
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new DurablePublicationTransactionException(
                    "DurablePublication requires a writable local database transaction");
        }
    }
}
