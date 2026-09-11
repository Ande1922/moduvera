package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.logging.ModuveraEcsStructuredLogFormatter;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.TraceContextCarrier;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.message.outbox.OutboxStore;
import io.github.ande1922.moduvera.message.outbox.OutboxWorker;
import io.github.ande1922.moduvera.message.outbox.PublicationObserver;
import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationPlan;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Shared real JDBC and safe receipt support; fault transports are explicitly supplied by each test. */
final class RelayFixtureSupport {
    static final boolean AGENT = Boolean.getBoolean("moduvera.test.agent");
    static final String TOPIC = "relay-events";
    static final String PRIVATE_BODY = "relay-private-body-sentinel";
    static final String PRIVATE_CAUSE = "relay-private-cause-sentinel";
    static final ContextKey<String> CONTEXT_KEY = ContextKey.named("relay-fixture");

    private RelayFixtureSupport() {}

    static OutboxWorker worker(OutboxStore store, MessageTransport transport, int maximum) {
        return worker(store, transport, maximum, true);
    }

    static OutboxWorker worker(OutboxStore store, MessageTransport transport, int maximum, boolean boundary) {
        return new OutboxWorker(store, transport, Clock.systemUTC(), System::nanoTime, Duration.ofSeconds(60),
                Duration.ofSeconds(2), Duration.ZERO, maximum, PublicationObserver.noop(),
                new RelayPublicationLifecycle(boundary ? Set.of(TOPIC) : Set.of()));
    }

    static SerializedMessage message(String id, TraceContextCarrier creation) {
        return SerializedMessage.json(new MessageDescriptor(new MessageId(id), MessageKind.EVENT,
                new MessageType("io.github.ande1922.moduvera.relay.created.v1"), URI.create("urn:service:origin"),
                new Destination(TOPIC), Instant.parse("2026-09-01T00:00:00Z"), new TenantId("origin-tenant"),
                new Actor(ActorType.SERVICE, "origin-service"), "original-" + id, new MessageId("original-cause"),
                new Initiator(ActorType.USER, "origin-user"), "origin-key", creation),
                "{\"body\":\"" + PRIVATE_BODY + "\"}");
    }

    static TraceContextCarrier historicalParent(String id, String kind) {
        Instant past = Instant.now().minus(Duration.ofDays(3));
        var span = GlobalOpenTelemetry.getTracer("moduvera.relay.fixture").spanBuilder("fixture.relay." + kind)
                .setNoParent().setAttribute("fixture.id", id).setStartTimestamp(past).startSpan();
        var values = new HashMap<String, String>();
        W3CTraceContextPropagator.getInstance().inject(Context.root().with(span), values, Map::put);
        span.end(past.plusMillis(1));
        String parent = values.get("traceparent");
        return parent == null ? null : new TraceContextCarrier(parent, values.get("tracestate"));
    }

    static <T> T inManagement(String id, Supplier<T> work) {
        var span = GlobalOpenTelemetry.getTracer("moduvera.relay.fixture").spanBuilder("fixture.relay.management")
                .setNoParent().setAttribute("fixture.id", id).startSpan();
        var identity = new ExecutionContext(new TenantId("management-tenant"), new Actor(ActorType.SERVICE, "management-service"),
                new Initiator(ActorType.USER, "management-user"), "management-" + id);
        Map<String, String> originalMdc = MDC.getCopyOfContextMap();
        MDC.put("fixture_probe", "pre-existing");
        try (var business = ExecutionContextHolder.open(identity);
                var scope = Context.root().with(span).with(CONTEXT_KEY, id).makeCurrent()) {
            var current = Context.current();
            var mdc = MDC.getCopyOfContextMap();
            try {
                return work.get();
            } finally {
                assertThat(Span.current().getSpanContext()).isEqualTo(Span.fromContext(current).getSpanContext());
                assertThat(Context.current().get(CONTEXT_KEY)).isSameAs(current.get(CONTEXT_KEY));
                assertThat(ExecutionContextHolder.require()).isSameAs(identity);
                assertThat(MDC.getCopyOfContextMap()).isEqualTo(mdc);
            }
        } finally {
            span.end();
            if (originalMdc == null) { MDC.clear(); } else { MDC.setContextMap(originalMdc); }
        }
    }

    static Map<String, Object> trace() {
        var span = Span.current().getSpanContext();
        return Map.of("trace", span.getTraceId(), "span", span.getSpanId(), "valid", span.isValid());
    }

    static void writeEvidence(String prefix, Object receipts, Logs logs) throws Exception {
        String directory = System.getenv("MODUVERA_OBSERVABILITY_EVIDENCE_DIR");
        if (directory != null) {
            write(Path.of(directory, prefix + "-receipts.json"), receipts);
            Files.writeString(Path.of(directory, prefix + "-logs.jsonl"), String.join("", logs.lines));
        }
    }

    static void write(Path path, Object value) throws Exception {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, new ObjectMapper().writeValueAsString(value));
        Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static final class Logs extends AppenderBase<ILoggingEvent> implements AutoCloseable {
        final List<String> lines = new CopyOnWriteArrayList<>();
        private final Path output;
        private final List<Logger> loggers = new ArrayList<>();
        private final Map<Logger, Level> previous = new HashMap<>();
        private final Map<Logger, Boolean> additive = new HashMap<>();
        private final ModuveraEcsStructuredLogFormatter formatter = new ModuveraEcsStructuredLogFormatter(new StandardEnvironment());

        Logs() {
            this(null);
        }

        Logs(Path output) {
            this.output = output;
            start();
            for (String name : List.of("task.execute", "mq.produce", "outbox.recovery", "outbox.publish.failure",
                    "mq.produce.propagating", "org.springframework.kafka.support.LoggingProducerListener")) {
                var logger = (Logger) LoggerFactory.getLogger(name);
                previous.put(logger, logger.getLevel());
                additive.put(logger, logger.isAdditive());
                // This synchronous production-formatter capture is the fixture sink, including non-Boot JDBC tests.
                logger.setAdditive(false);
                logger.setLevel(Level.DEBUG);
                logger.addAppender(this);
                loggers.add(logger);
            }
        }

        @Override protected synchronized void append(ILoggingEvent event) {
            String line = formatter.format(event);
            lines.add(line);
            if (output != null) {
                try {
                    Files.writeString(output, line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException("cannot persist fixture log checkpoint", failure);
                }
            }
        }

        List<JsonNode> events(String id, String logger) {
            var mapper = new ObjectMapper();
            return lines.stream().map(mapper::readTree).filter(event -> event.path("message_id").asString().equals(id))
                    .filter(event -> event.path("log").path("logger").asString().equals(logger)).toList();
        }

        @Override public void close() {
            for (var logger : loggers) {
                logger.detachAppender(this);
                logger.setLevel(previous.get(logger));
                logger.setAdditive(additive.get(logger));
            }
            stop();
        }
    }

    static final class Database {
        final String name;
        final DataSource source;
        final JdbcTemplate jdbc;
        final JdbcTemplate external;
        final TransactionTemplate transactions;
        final JdbcOutboxStore store;

        Database(String url, String username, String password, JdbcMessagingDialect dialect) {
            name = dialect.name().toLowerCase(java.util.Locale.ROOT);
            source = new DriverManagerDataSource(url, username, password);
            jdbc = new JdbcTemplate(source);
            external = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
            transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            new DatabaseMigrator(source).migrate(new MigrationPlan(new DatabaseComponent("messaging"),
                    List.of("classpath:db/moduvera-messaging/" + name), true));
            store = new JdbcOutboxStore(new NamedParameterJdbcTemplate(source), dialect, transactions, new LocalOutboxWakeSignal());
        }

        String seed(String operation) {
            jdbc.update("DELETE FROM moduvera_message_outbox");
            String id = name + "-" + operation;
            var creation = historicalParent(id, "creation");
            var publication = historicalParent(id, "publication");
            transactions.executeWithoutResult(status -> store.appendIntent(message(id, creation)));
            jdbc.update("UPDATE moduvera_message_outbox SET publication_traceparent = ?, publication_tracestate = ?, publication_generation = 7",
                    publication == null ? null : publication.traceParent(), publication == null ? null : publication.traceState());
            return id;
        }

        void expire() {
            jdbc.update("UPDATE moduvera_message_outbox SET claim_expires_at = ?", java.sql.Timestamp.from(Instant.EPOCH));
        }

        Map<String, Object> row() {
            var row = external.queryForMap("SELECT * FROM moduvera_message_outbox");
            var safe = new HashMap<String, Object>();
            for (String key : List.of("message_id", "correlation_id", "status", "attempt_count", "publication_generation",
                    "publication_traceparent", "publication_tracestate", "creation_traceparent", "creation_tracestate")) {
                safe.put(key, row.get(key));
            }
            return safe;
        }
    }
}
