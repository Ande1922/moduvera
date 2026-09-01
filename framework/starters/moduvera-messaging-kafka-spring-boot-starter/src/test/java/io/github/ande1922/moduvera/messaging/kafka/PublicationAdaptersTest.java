package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.publication.DurablePublicationTransactionException;
import io.github.ande1922.moduvera.message.publication.ImmediatePublicationInTransactionException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.Connection;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PublicationAdaptersTest {

    @Test
    void durablePublicationAppendsInsideTheTransactionBoundToItsDataSource() {
        DataSource dataSource = dataSource();
        AtomicInteger appends = new AtomicInteger();
        var publication = new JdbcDurablePublication(dataSource, ignored -> appends.incrementAndGet());

        inTransaction(dataSource, false, () -> publication.append(message()));

        assertThat(appends).hasValue(1);
    }

    @Test
    void durablePublicationRejectsMissingReadOnlyAndWrongDataSourceTransactions() {
        DataSource outboxDataSource = dataSource();
        DataSource otherDataSource = dataSource();
        AtomicInteger appends = new AtomicInteger();
        var publication =
                new JdbcDurablePublication(outboxDataSource, ignored -> appends.incrementAndGet());
        SerializedMessage message = message();

        assertThatThrownBy(() -> publication.append(message))
                .isInstanceOf(DurablePublicationTransactionException.class)
                .hasMessageContaining("active local database transaction");

        assertThatThrownBy(() -> inTransaction(
                        outboxDataSource, true, () -> publication.append(message)))
                .isInstanceOf(DurablePublicationTransactionException.class)
                .hasMessageContaining("writable");

        assertThatThrownBy(() -> inTransaction(
                        otherDataSource, false, () -> publication.append(message)))
                .isInstanceOf(DurablePublicationTransactionException.class)
                .hasMessageContaining("outbox DataSource");
        assertThat(appends).hasValue(0);
    }

    @Test
    void immediatePublicationSendsOnceOutsideTransactionsAndRejectsActiveTransactions()
            {
        DataSource dataSource = dataSource();
        AtomicInteger sends = new AtomicInteger();
        var publication = new KafkaImmediatePublication(ignored -> sends.incrementAndGet());
        SerializedMessage message = message();

        publication.publish(message);
        assertThat(sends).hasValue(1);

        assertThatThrownBy(() -> inTransaction(
                        dataSource, false, () -> publication.publish(message)))
                .isInstanceOf(ImmediatePublicationInTransactionException.class);
        assertThat(sends).hasValue(1);
    }

    @Test
    void immediatePublicationDoesNotRetryOrHideTheTransportOutcome() {
        var failure = new IllegalStateException("broker outcome unknown");
        var publication = new KafkaImmediatePublication(ignored -> {
            throw failure;
        });

        assertThatThrownBy(() -> publication.publish(message()))
                .isSameAs(failure);
    }

    @SuppressWarnings("PMD.CloseResource") // The helper releases the synthetic transaction resource below.
    private static void inTransaction(DataSource dataSource, boolean readOnly, Runnable work) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(connection));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        try {
            work.run();
        } finally {
            TransactionSynchronizationManager.unbindResource(dataSource);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static DataSource dataSource() {
        Connection connection = (Connection) Proxy.newProxyInstance(
                PublicationAdaptersTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isClosed", "getAutoCommit" -> false;
                    case "toString" -> "PublicationAdaptersTestConnection";
                    default -> primitiveDefault(method.getReturnType());
                });
        return new SingleConnectionDataSource(connection, true);
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private static SerializedMessage message() {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId("message-1"),
                        MessageKind.EVENT,
                        new MessageType("io.github.ande1922.moduvera.test.event.v1"),
                        URI.create("urn:service:test"),
                        new Destination("test.events"),
                        Instant.parse("2026-09-01T00:00:00Z"),
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "test-service"),
                        "correlation-1",
                        null,
                        new Initiator(ActorType.SYSTEM, "test"),
                        "tenant-a:key-1"),
                "{}");
    }
}
