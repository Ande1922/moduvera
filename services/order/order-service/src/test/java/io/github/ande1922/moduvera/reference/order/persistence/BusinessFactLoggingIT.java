package io.github.ande1922.moduvera.reference.order.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.EchoEncoder;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.NestedTransactionBoundaryException;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import io.github.ande1922.moduvera.messaging.kafka.LocalOutboxWakeSignal;
import io.github.ande1922.moduvera.messaging.kafka.JdbcDurablePublication;
import io.github.ande1922.moduvera.messaging.kafka.JdbcInboxRepository;
import io.github.ande1922.moduvera.messaging.kafka.JdbcMessagingDialect;
import io.github.ande1922.moduvera.messaging.kafka.JdbcOutboxStore;
import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationPlan;
import io.github.ande1922.moduvera.reference.catalog.api.ProductSnapshot;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.messaging.OutboxInventoryResultPublisher;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence.MybatisInventoryStore;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryReservationHandler;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryResultPublisher;
import io.github.ande1922.moduvera.reference.inventory.domain.AllOrNothingReservationPolicy;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.messaging.OutboxReserveInventoryPublisher;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence.MybatisOrderRepository;
import io.github.ande1922.moduvera.reference.order.api.CreateOrderCommand;
import io.github.ande1922.moduvera.reference.order.api.CreateOrderLine;
import io.github.ande1922.moduvera.reference.order.api.GetOrderQuery;
import io.github.ande1922.moduvera.reference.order.application.InventoryResultHandler;
import io.github.ande1922.moduvera.reference.order.application.OrderApplicationService;
import io.github.ande1922.moduvera.reference.order.application.ReserveInventoryPublisher;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = MySqlBusinessRepositoriesIT.TestApplication.class,
        properties = "spring.application.name=business-fact-test")
class BusinessFactLoggingIT {

    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String SPAN_ID = "0123456789abcdef";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CreateOrderCommand CREATE = new CreateOrderCommand(List.of(new CreateOrderLine(7, 2)));
    private static final ExecutionContext CONTEXT = ExecutionContext.initiatedBy(
            new TenantId("tenant-facts"),
            new Actor(ActorType.USER, "fact-user", Set.of("order:create", "order:read", "inventory:reserve",
                    "order:apply-inventory-result")), "fact-correlation");

    @Container
    private static final MySQLContainer MYSQL =
            new MySQLContainer(System.getProperty("mysql.test.image", "mysql:8.4.11"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.flyway.enabled", () -> false);
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MybatisOrderRepository orders;
    @Autowired private MybatisInventoryStore inventory;
    @Autowired private TransactionBoundary transactions;
    @Autowired private PlatformTransactionManager transactionManager;

    private ReserveInventoryPublisher reservePublisher;
    private InventoryResultPublisher resultPublisher;
    private JdbcInboxRepository inbox;

    @BeforeAll
    void setupAdapters() {
        var migrator = new DatabaseMigrator(dataSource);
        for (String component : List.of("order", "inventory", "messaging")) {
            String location = component.equals("messaging")
                    ? "classpath:db/moduvera-messaging/mysql" : "classpath:db/migration/" + component + "-mysql";
            migrator.migrate(new MigrationPlan(new DatabaseComponent(component), List.of(location), true));
        }
        var named = new NamedParameterJdbcTemplate(dataSource);
        var store = new JdbcOutboxStore(named, JdbcMessagingDialect.MYSQL,
                new TransactionTemplate(transactionManager), new LocalOutboxWakeSignal());
        var publication = new JdbcDurablePublication(dataSource, store);
        reservePublisher = new OutboxReserveInventoryPublisher(publication, JSON, CLOCK);
        resultPublisher = new OutboxInventoryResultPublisher(publication, JSON, CLOCK);
        inbox = new JdbcInboxRepository(named, JdbcMessagingDialect.MYSQL);
    }

    @BeforeEach
    void clean() {
        for (String table : List.of("moduvera_message_inbox", "moduvera_message_outbox",
                "inventory_reservation_result", "inventory_stock", "order_line", "order_header")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("""
                INSERT INTO inventory_stock(tenant_id, product_id, available, version,
                    created_at, created_by, updated_at, updated_by)
                VALUES ('tenant-facts', 7, 5, 0, ?, 'fixture', ?, 'fixture')
                """, NOW, NOW);
    }

    @Test
    void committedFactsHaveTrustedContextAndDuplicatesAndQueriesAreSilent(CapturedOutput output) {
        String expectedSpanId = run(() -> {
            var service = service(42, reservePublisher);
            service.create(CREATE);
            service.get(new GetOrderQuery(42));
            var reserve = reservation(resultPublisher);
            var command = reserveCommand("reserve-order-42", 42, 2);
            reserve.handle(command, new MessageId("reserve-1"));
            reserve.handle(command, new MessageId("reserve-1"));
            reserve.handle(command, new MessageId("reserve-duplicate-command"));
            var results = results();
            var result = new InventoryReserved(command.commandId(), 42, NOW);
            results.handle(result, new MessageId("result-1"));
            results.handle(result, new MessageId("result-1"));
            results.handle(result, new MessageId("result-duplicate-state"));
        });
        List<JsonNode> facts = facts(output);
        assertThat(facts).extracting(event -> event.path("event").path("action").asString())
                .containsExactly("order_created", "inventory_reserved", "order_confirmed");
        assertThat(facts).allSatisfy(event -> {
            assertThat(event.path("correlation_id").asString()).isEqualTo("fact-correlation");
            assertThat(event.path("trace_id").asString()).isEqualTo(TRACE_ID);
            assertThat(event.path("span_id").asString()).isEqualTo(expectedSpanId);
            assertThat(event.path("tenant_id").asString()).isEqualTo("tenant-facts");
            assertThat(event.path("actor_id").asString()).isEqualTo("fact-user");
            assertThat(event.path("initiator_id").asString()).isEqualTo("fact-user");
            assertThat(event.path("user_id").asString()).isEqualTo("fact-user");
            assertThat(event.path("log").path("level").asString()).isEqualTo("INFO");
            assertThat(event.path("order_id").asLong()).isEqualTo(42);
            assertThat(event.toString()).doesNotContain("password", "secret-payload", "stack_trace");
        });
        assertThat(count("moduvera_message_outbox")).isEqualTo(2);
        assertThat(count("moduvera_message_inbox")).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT available FROM inventory_stock", Integer.class)).isEqualTo(3);
        assertThat(ExecutionContextHolder.current()).isEmpty();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void persistedRejectionIsOneExpectedResultAndOrderTransitionIsOneFact(CapturedOutput output) {
        run(() -> {
            service(42, reservePublisher).create(CREATE);
            var command = reserveCommand("rejected", 42, 9);
            var reservation = reservation(resultPublisher);
            reservation.handle(command, new MessageId("rejection-1"));
            reservation.handle(command, new MessageId("rejection-2"));
            results().handle(new InventoryRejected("rejected", 42, List.of(7L), NOW), new MessageId("result"));
        });
        assertThat(facts(output)).extracting(event -> event.path("event").path("action").asString())
                .containsExactly("order_created", "", "order_rejected");
        JsonNode rejection = facts(output).get(1);
        assertThat(rejection.path("error").path("code").asString()).isEqualTo("BIZ_INVENTORY_STOCK_UNAVAILABLE");
        assertThat(rejection.path("error").has("stack_trace")).isFalse();
        assertThat(jdbc.queryForObject("SELECT available FROM inventory_stock", Integer.class)).isEqualTo(5);
    }

    @Test
    void rollbackAfterAppendAndOuterTransactionEmitNoSuccess(CapturedOutput output) {
        run(() -> {
            assertThatThrownBy(() -> service(42, command -> {
                reservePublisher.publish(command);
                throw new IllegalStateException("secret-payload password=secret SELECT * FROM private_data");
            }).create(CREATE)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    service(43, reservePublisher).create(CREATE)))
                    .isInstanceOf(NestedTransactionBoundaryException.class);
            assertThatThrownBy(() -> reservation(result -> {
                resultPublisher.publish(result);
                throw new IllegalStateException("private-result-payload");
            }).handle(reserveCommand("rollback-reserve", 44, 2), new MessageId("rollback")))
                    .isInstanceOf(IllegalStateException.class);
        });
        assertThat(facts(output)).isEmpty();
        assertThat(count("order_header")).isZero();
        assertThat(count("inventory_reservation_result")).isZero();
        assertThat(count("moduvera_message_inbox")).isZero();
        assertThat(count("moduvera_message_outbox")).isZero();
        assertThat(output.getOut()).doesNotContain("secret-payload", "private-result-payload", "SELECT * FROM private_data");
    }

    @Test
    void realCommitFailureAfterSuccessfulWritesEmitsNoFact(CapturedOutput output) {
        run(() -> assertThatThrownBy(() -> service(42, command -> {
            reservePublisher.publish(command);
            long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    var observer = new JdbcTemplate(new DriverManagerDataSource(
                            MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
                    observer.execute("KILL CONNECTION " + connectionId);
                }
            });
        }).create(CREATE)).isInstanceOf(TransactionSystemException.class));
        assertThat(facts(output)).isEmpty();
        assertThat(count("order_header")).isZero();
        assertThat(count("moduvera_message_outbox")).isZero();
    }

    @Test
    void failedLogOutputDoesNotReplayCommittedBusiness(CapturedOutput output) {
        Logger logger = (Logger) LoggerFactory.getLogger(InventoryReservationHandler.class);
        var failing = new OutputStreamAppender<ILoggingEvent>();
        failing.setContext(logger.getLoggerContext());
        failing.setName("failed-output-fixture");
        failing.setEncoder(new EchoEncoder<>());
        failing.setOutputStream(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("output unavailable");
            }
        });
        failing.start();
        logger.addAppender(failing);
        try {
            run(() -> {
                var handler = reservation(resultPublisher);
                var command = reserveCommand("output-failure", 42, 2);
                handler.handle(command, new MessageId("output-failure"));
                handler.handle(command, new MessageId("output-failure"));
            });
            assertThat(failing.isStarted()).isFalse();
            assertThat(facts(output)).hasSize(1);
            assertThat(count("moduvera_message_outbox")).isOne();
            assertThat(count("moduvera_message_inbox")).isOne();
        } finally {
            logger.detachAppender(failing);
            failing.stop();
        }
    }

    private OrderApplicationService service(long orderId, ReserveInventoryPublisher publisher) {
        return new OrderApplicationService(
                query -> new ProductSnapshot(7, "secret-payload", BigDecimal.ONE, Currency.getInstance("CNY"), 0),
                orders, publisher, () -> orderId, transactions, new UseCaseAuthorizer(), CLOCK);
    }

    private InventoryReservationHandler reservation(InventoryResultPublisher publisher) {
        return new InventoryReservationHandler(new InboxTemplate("inventory", inbox, transactions, CLOCK),
                inventory, new UseCaseAuthorizer(), CLOCK, publisher, new AllOrNothingReservationPolicy());
    }

    private InventoryResultHandler results() {
        return new InventoryResultHandler(new InboxTemplate("order", inbox, transactions, CLOCK),
                orders, new UseCaseAuthorizer());
    }

    private static ReserveInventoryCommand reserveCommand(String command, long order, int quantity) {
        return new ReserveInventoryCommand(command, order, List.of(new ReserveInventoryLine(7, quantity)));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static String run(Runnable work) {
        var parent = Span.wrap(SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault()));
        try (var parentScope = parent.makeCurrent()) {
            Span span = Boolean.getBoolean("business.facts.agent")
                    ? GlobalOpenTelemetry.getTracer("business-fact-verification")
                            .spanBuilder("business-fact-attempt").startSpan() : parent;
            if (Boolean.getBoolean("business.facts.agent")) {
                assertThat(span.isRecording()).isTrue();
            }
            try (var ignored = span.makeCurrent()) {
                ExecutionContextHolder.run(CONTEXT, work);
                return span.getSpanContext().getSpanId();
            } finally {
                span.end();
            }
        }
    }

    private static List<JsonNode> facts(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.startsWith("{"))
                .map(JSON::readTree)
                .filter(event -> event.path("log").path("logger").asString().equals(OrderApplicationService.class.getName())
                        || event.path("log").path("logger").asString().equals(InventoryReservationHandler.class.getName())
                        || event.path("log").path("logger").asString().equals(InventoryResultHandler.class.getName()))
                .toList();
    }
}
