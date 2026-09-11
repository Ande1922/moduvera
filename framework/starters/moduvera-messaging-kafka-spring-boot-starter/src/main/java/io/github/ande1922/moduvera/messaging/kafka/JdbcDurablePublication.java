package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.logging.LoggingContextSnapshot;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.TraceContextCarrier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import java.util.HashMap;
import java.util.Map;
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
            appendObserved(message);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private void appendObserved(SerializedMessage message) {
        var span = GlobalOpenTelemetry.getTracer("io.github.ande1922.moduvera.messaging")
                .spanBuilder("outbox.append").setSpanKind(SpanKind.INTERNAL)
                .setAttribute("messaging.message.id", message.descriptor().id().value()).startSpan();
        try (var trace = span.makeCurrent(); var logging = LoggingContextSnapshot.captureAllowingAbsent().openScope()) {
            appendIntent.accept(withCreation(message));
        } catch (RuntimeException | Error failure) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute("error.type", failure.getClass().getName());
            throw failure;
        } finally {
            // This ends only the append call, not its enclosing transaction or any Broker interaction.
            span.end();
        }
    }

    private static SerializedMessage withCreation(SerializedMessage message) {
        var descriptor = message.descriptor();
        if (descriptor.creationContext() != null) {
            return message;
        }
        Map<String, String> values = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), values, Map::put);
        String parent = values.get("traceparent");
        if (parent == null) {
            return message;
        }
        var creation = new TraceContextCarrier(parent, values.get("tracestate"));
        var captured = new MessageDescriptor(descriptor.id(), descriptor.kind(), descriptor.type(), descriptor.source(),
                descriptor.destination(), descriptor.time(), descriptor.tenantId(), descriptor.actor(), descriptor.correlationId(),
                descriptor.causationId(), descriptor.initiator(), descriptor.partitionKey(), creation);
        return new SerializedMessage(captured, message.contentType(), message.payload());
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
