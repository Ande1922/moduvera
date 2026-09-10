package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.spring.SpringTransactionBoundary;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.TraceContextCarrier;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationPlan;
import io.github.ande1922.moduvera.testing.ProgressBarrier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Context;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.testcontainers.images.builder.Transferable;
import org.springframework.test.annotation.DirtiesContext;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@DirtiesContext
@SpringBootTest(classes = InboundCausalityIT.FixtureConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class InboundCausalityIT {

    private static final String TOPIC = "inbound-causality";
    private static final String GROUP = "inbound-causality-it";
    private static final String CREATION = "00-11111111111111111111111111111111-2222222222222222-01";
    private static final String PRIVATE_PAYLOAD = "inbound-private-body-sentinel";
    private static final String PRIVATE_CAUSE = "inbound-private-cause-sentinel";
    private static final List<Map<String, Object>> RECEIPTS = new CopyOnWriteArrayList<>();
    private static final Map<String, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();
    private static final CountDownLatch PUBLISHED = new CountDownLatch(1);
    private static final AtomicInteger BATCH = new AtomicInteger();
    private static final ContextKey<String> EXTRA_CONTEXT = ContextKey.named("inbound-fixture-extra");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image", "postgres:18.6"));

    @Container
    @SuppressWarnings("deprecation")
    private static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse(System.getProperty("kafka.test.image", "confluentinc/cp-kafka:7.3.3")));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
        properties.add("spring.cloud.function.definition", () -> "observe");
        properties.add("spring.cloud.stream.output-bindings", () -> "send-out-0");
        properties.add("spring.cloud.stream.bindings.observe-in-0.destination", () -> TOPIC);
        properties.add("spring.cloud.stream.bindings.observe-in-0.group", () -> GROUP);
        properties.add("spring.cloud.stream.bindings.observe-in-0.consumer.max-attempts", () -> 1);
        properties.add("spring.cloud.stream.bindings.observe-in-0.consumer.use-native-decoding", () -> true);
        properties.add("spring.cloud.stream.kafka.bindings.observe-in-0.consumer.configuration.interceptor.classes",
                () -> RawTransportProbe.class.getName());
        properties.add("spring.cloud.stream.bindings.send-out-0.destination", () -> TOPIC);
        properties.add("spring.cloud.stream.bindings.send-out-0.producer.use-native-encoding", () -> true);
        properties.add("spring.cloud.stream.kafka.bindings.observe-in-0.consumer.enable-dlq", () -> true);
        properties.add("spring.cloud.stream.kafka.bindings.observe-in-0.consumer.dlq-name", () -> TOPIC + "-dlq");
        for (String kind : List.of("key", "value")) {
            properties.add("spring.cloud.stream.kafka.bindings.observe-in-0.consumer.dlq-producer-properties.configuration."
                            + kind + ".serializer", () -> "org.apache.kafka.common.serialization.ByteArraySerializer");
        }
        properties.add("spring.cloud.stream.kafka.bindings.send-out-0.producer.sync", () -> true);
        properties.add("spring.cloud.stream.kafka.bindings.send-out-0.producer.configuration.key.serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
        properties.add("spring.cloud.stream.kafka.bindings.send-out-0.producer.configuration.linger.ms", () -> 0);
        properties.add("spring.cloud.stream.kafka.bindings.send-out-0.producer.configuration.acks", () -> "all");
        for (String timeout : List.of("delivery.timeout.ms", "request.timeout.ms", "max.block.ms")) {
            properties.add("spring.cloud.stream.kafka.bindings.send-out-0.producer.configuration." + timeout,
                    () -> 1500);
        }
        properties.add("moduvera.messaging.kafka.routes." + TOPIC, () -> "send-out-0");
        properties.add("moduvera.messaging.kafka.consumer-bindings[0]", () -> "observe-in-0");
        properties.add("moduvera.messaging.kafka.consumer-max-attempts", () -> 3);
        properties.add("moduvera.messaging.kafka.consumer-backoff-initial", () -> "1ms");
        properties.add("moduvera.messaging.kafka.consumer-backoff-max", () -> "1ms");
        properties.add("moduvera.messaging.kafka.relay-enabled", () -> false);
    }

    @Autowired
    private MessageTransport transport;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Consumer<Message<byte[]>> observe;

    @Test
    void realTransportInboxRetryAndInterleavedIdentities() throws Exception {
        assertThat(transport).isInstanceOf(StreamBridgeMessageTransport.class);
        ProgressBarrier progress = ProgressBarrier.capture(InboundCausalityIT::consumed);
        List<SerializedMessage> messages = new ArrayList<>();
        messages.add(message("a-one", "tenant-a", CREATION));
        messages.add(message("a-two", "tenant-a", CREATION));
        messages.add(message("b-one", "tenant-b", CREATION));
        messages.add(message("retry", "tenant-b", CREATION));
        messages.add(message("terminal", "tenant-a", CREATION));
        messages.add(message("exhausted", "tenant-b", CREATION));
        messages.add(message("invalid-creation", "tenant-a", "invalid-trace"));
        messages.add(message("no-creation", "tenant-b", null));
        messages.add(messages.getFirst());
        try {
            for (SerializedMessage message : messages) {
                Span operation = GlobalOpenTelemetry.getTracer("moduvera.inbound.fixture")
                        .spanBuilder("fixture.publish").setNoParent().startSpan();
                try (var ignored = operation.makeCurrent()) {
                    transport.send(message);
                } finally {
                    operation.end();
                }
            }
        } finally {
            PUBLISHED.countDown();
        }
        Context unsampled = Context.root().with(Span.wrap(SpanContext.create(
                "33333333333333333333333333333333", "4444444444444444", TraceFlags.getDefault(), TraceState.getDefault())));
        try (var ignored = unsampled.makeCurrent()) {
            transport.send(message("unsampled", "tenant-b", CREATION));
        }
        sendWithoutAgent(message("missing-parent", "tenant-a", CREATION), false);
        sendWithoutAgent(message("invalid-parent", "tenant-b", CREATION), true);
        progress.awaitAdvanceBy(messages.size() + 3, Duration.ofSeconds(60), Duration.ofMillis(50),
                () -> "observed attempts=" + ATTEMPTS);
        try (var ignored = Context.root().makeCurrent()) {
            observe.accept(new KafkaMessageMapper().toSpringMessage(message("no-current-context", "tenant-a", CREATION)));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture_inbound_business", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moduvera_message_inbox", Integer.class)).isEqualTo(10);
        assertThat(ATTEMPTS.get("a-one")).hasValue(2);
        assertThat(ATTEMPTS.get("retry")).hasValue(3);
        assertThat(ATTEMPTS.get("terminal")).hasValue(1);
        assertThat(ATTEMPTS.get("exhausted")).hasValue(3);
        assertThat(RECEIPTS.stream().filter(row -> row.get("phase").equals("restored"))).hasSize(13);
        String evidence = System.getenv("MODUVERA_OBSERVABILITY_EVIDENCE_DIR");
        if (evidence != null) {
            Files.writeString(Path.of(evidence, "inbound-receipts.json"),
                    new ObjectMapper().writeValueAsString(Map.of(
                            "baseline", progress.baseline(), "committed", consumed(), "receipts", RECEIPTS)));
        }
    }

    private static void sendWithoutAgent(SerializedMessage message, boolean invalidParent) throws Exception {
        byte[] envelope = new KafkaMessageMapper().toSpringMessage(message).getPayload();
        String input = "contentType:application/cloudevents+json"
                + (invalidParent ? ",traceparent:invalid-transport\t" : "\t")
                + new String(envelope, StandardCharsets.UTF_8) + "\n";
        String path = "/tmp/" + message.descriptor().id().value() + ".json";
        KAFKA.copyFileToContainer(Transferable.of(input.getBytes(StandardCharsets.UTF_8)), path);
        var result = KAFKA.execInContainer("bash", "-c",
                "kafka-console-producer --bootstrap-server localhost:9092 --topic " + TOPIC
                        + " --property parse.headers=true < " + path);
        assertThat(result.getExitCode()).as("un-instrumented producer exit").isZero();
    }

    /** Captures Broker record headers before the Agent adapts Spring Message headers. */
    public static class RawTransportProbe implements ConsumerInterceptor<byte[], byte[]> {
        @Override
        public ConsumerRecords<byte[], byte[]> onConsume(ConsumerRecords<byte[], byte[]> records) {
            try {
                if (!records.isEmpty() && !PUBLISHED.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("fixture publication did not finish");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fixture probe interrupted", interrupted);
            }
            ObjectMapper json = new ObjectMapper();
            int batch = records.isEmpty() ? BATCH.get() : BATCH.incrementAndGet();
            records.forEach(record -> {
                var envelope = json.readTree(record.value());
                var row = new LinkedHashMap<String, Object>();
                row.put("phase", "broker-wire");
                row.put("id", envelope.get("id").asString());
                row.put("offset", record.offset());
                row.put("batch_size", records.count());
                row.put("batch_id", batch);
                row.put("body_size", record.value().length);
                var parent = record.headers().lastHeader("traceparent");
                row.put("traceparent", parent == null ? "absent"
                        : new String(parent.value(), StandardCharsets.UTF_8));
                row.put("creation", envelope.has("traceparent") ? envelope.get("traceparent").asString() : "absent");
                RECEIPTS.add(row);
            });
            return records;
        }

        @Override
        public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) { }

        @Override
        public void close() { }

        @Override
        public void configure(Map<String, ?> configuration) { }
    }

    private static long consumed() {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            return admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).entrySet().stream()
                    .filter(entry -> TOPIC.equals(entry.getKey().topic()))
                    .mapToLong(entry -> entry.getValue().offset()).sum();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot observe fixture consumer progress", failure);
        }
    }

    private static SerializedMessage message(String id, String tenant, String creation) {
        return SerializedMessage.json(new MessageDescriptor(new MessageId(id), MessageKind.EVENT,
                new MessageType("io.moduvera.fixture.inbound.v1"), URI.create("urn:fixture:producer"),
                new Destination(TOPIC), Instant.parse("2026-09-10T00:00:00Z"), new TenantId(tenant),
                new Actor(ActorType.SERVICE, "wire-producer", Set.of("wire:forbidden")), "corr-" + id,
                null, new Initiator(ActorType.USER, "user-" + id), "single-partition",
                creation == null ? null : new TraceContextCarrier(creation, null)),
                "{\"private\":\"" + PRIVATE_PAYLOAD + "\"}");
    }

    private static Map<String, Object> receipt(String phase, String id) {
        var row = new LinkedHashMap<String, Object>();
        row.put("phase", phase);
        row.put("id", id);
        row.put("trace", Span.current().getSpanContext().getTraceId());
        row.put("span", Span.current().getSpanContext().getSpanId());
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class FixtureConfiguration {

        @Bean
        ObjectMapper fixtureObjectMapper() {
            return new ObjectMapper();
        }

        @Bean
        InboxTemplate fixtureInbox(DataSource dataSource, InboxRepository repository,
                PlatformTransactionManager transactions) {
            new DatabaseMigrator(dataSource).migrate(new MigrationPlan(new DatabaseComponent("messaging"),
                    List.of("classpath:db/moduvera-messaging/postgresql"), true));
            new JdbcTemplate(dataSource).execute("""
                    CREATE TABLE fixture_inbound_business (
                        tenant_id VARCHAR(64) NOT NULL, message_id VARCHAR(128) NOT NULL,
                        PRIMARY KEY (tenant_id, message_id))
                    """);
            return new InboxTemplate(GROUP, repository,
                    new SpringTransactionBoundary(new TransactionTemplate(transactions)), Clock.systemUTC());
        }

        @Bean
        Consumer<Message<byte[]>> observe(ReliableMessageConsumerFactory factory, KafkaMessageMapper mapper,
                InboxTemplate fixtureInbox, JdbcTemplate jdbc) {
            var contract = new InboundMessageContract(MessageKind.EVENT,
                    new MessageType("io.moduvera.fixture.inbound.v1"), URI.create("urn:fixture:producer"),
                    new Destination(TOPIC), new Actor(ActorType.SERVICE, "local-consumer", Set.of("local:consume")));
            var endpoint = factory.forContract(contract, message -> {
                String id = message.descriptor().id().value();
                ExecutionContext identity = ExecutionContextHolder.require();
                assertThat(identity.correlationId()).isEqualTo("corr-" + id);
                assertThat(identity.tenantId()).isEqualTo(message.descriptor().tenantId());
                assertThat(identity.actor().subjectId()).isEqualTo("local-consumer");
                assertThat(identity.actor().permissions()).containsExactly("local:consume");
                assertThat(identity.initiator().subjectId()).isEqualTo("user-" + id);
                assertThat(MDC.get("correlation_id")).isEqualTo(identity.correlationId());
                assertThat(MDC.get("tenant_id")).isEqualTo(identity.requireTenantId().value());
                assertThat(MDC.get("actor_id")).isEqualTo("local-consumer");
                assertThat(MDC.get("initiator_id")).isEqualTo("user-" + id);
                assertThat(Context.current().get(EXTRA_CONTEXT)).isEqualTo("transport-extra");
                int attempt = ATTEMPTS.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
                var row = receipt("attempt", id);
                row.put("attempt", attempt);
                row.put("tenant", identity.requireTenantId().value());
                row.put("correlation", identity.correlationId());
                row.put("actor", identity.actor().subjectId());
                row.put("initiator", identity.initiator().subjectId());
                RECEIPTS.add(row);
                fixtureInbox.handle(message.descriptor().id(), () -> {
                    jdbc.update("INSERT INTO fixture_inbound_business (tenant_id, message_id) VALUES (?, ?)",
                            identity.requireTenantId().value(), id);
                    if (id.equals("terminal")) {
                        throw new NonRetryableMessageException(PRIVATE_CAUSE);
                    }
                    if (id.equals("exhausted") || (id.equals("retry") && attempt < 3)) {
                        throw new IllegalStateException(PRIVATE_CAUSE);
                    }
                });
            });
            return wire -> {
                SerializedMessage message = mapper.fromSpringMessage(wire);
                String id = message.descriptor().id().value();
                Context previous = Context.current();
                var previousIdentity = ExecutionContextHolder.current();
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                var row = receipt("transport", id);
                Object header = wire.getHeaders().get("traceparent");
                row.put("traceparent", header instanceof byte[] bytes
                        ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(header));
                row.put("body_size", wire.getPayload().length);
                row.put("creation", message.descriptor().creationContext() == null
                        ? "absent" : message.descriptor().creationContext().traceParent());
                RECEIPTS.add(row);
                try (var extended = previous.with(EXTRA_CONTEXT, "transport-extra").makeCurrent()) {
                    try {
                        endpoint.accept(wire);
                    } finally {
                        assertThat(Context.current().get(EXTRA_CONTEXT)).isEqualTo("transport-extra");
                        assertThat(Span.current().getSpanContext())
                                .isEqualTo(Span.fromContext(previous).getSpanContext());
                        assertThat(ExecutionContextHolder.current()).isEqualTo(previousIdentity);
                        Map<String, String> restoredMdc = MDC.getCopyOfContextMap();
                        assertThat(restoredMdc == null ? Map.<String, String>of() : restoredMdc)
                                .isEqualTo(previousMdc == null ? Map.<String, String>of() : previousMdc);
                        RECEIPTS.add(receipt("restored", id));
                    }
                }
                assertThat(Context.current().get(EXTRA_CONTEXT)).isEqualTo(previous.get(EXTRA_CONTEXT));
            };
        }
    }
}
