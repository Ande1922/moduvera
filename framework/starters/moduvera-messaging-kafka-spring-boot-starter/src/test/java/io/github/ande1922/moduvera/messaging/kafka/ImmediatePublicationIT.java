package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.TraceContextCarrier;
import io.github.ande1922.moduvera.message.publication.ImmediatePublication;
import io.github.ande1922.moduvera.message.publication.ImmediatePublicationInTransactionException;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@DirtiesContext
@SpringBootTest(classes = ImmediatePublicationIT.FixtureConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class ImmediatePublicationIT {
    private static final String BOUNDARY = "immediate-boundary";
    private static final String INTERNAL = "immediate-internal";
    private static final String PRIVATE_BODY = "immediate-private-body-sentinel";
    private static final List<Map<String, Object>> RECEIPTS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger SENDS = new AtomicInteger();
    private static final TraceContextCarrier EXISTING = new TraceContextCarrier(
            "00-" + "3".repeat(32) + "-" + "4".repeat(16) + "-01", "vendor=original");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("postgresql.test.image", "postgres:18.6"));
    @Container
    @SuppressWarnings("deprecation")
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(
            System.getProperty("kafka.test.image", "confluentinc/cp-kafka:7.3.3")));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
        properties.add("spring.cloud.stream.output-bindings", () -> "boundary-out-0;internal-out-0");
        for (String route : List.of("boundary", "internal")) {
            String binding = route + "-out-0";
            properties.add("moduvera.messaging.kafka.routes.immediate-" + route, () -> binding);
            properties.add("spring.cloud.stream.bindings." + binding + ".destination", () -> "immediate-" + route);
            properties.add("spring.cloud.stream.bindings." + binding + ".producer.use-native-encoding", () -> true);
            String prefix = "spring.cloud.stream.kafka.bindings." + binding + ".producer.";
            properties.add(prefix + "sync", () -> true);
            properties.add(prefix + "configuration.key.serializer", () -> "org.apache.kafka.common.serialization.StringSerializer");
            properties.add(prefix + "configuration.interceptor.classes", () -> SendProbe.class.getName());
            properties.add(prefix + "configuration.linger.ms", () -> 0);
            properties.add(prefix + "configuration.acks", () -> "all");
            for (String timeout : List.of("delivery.timeout.ms", "request.timeout.ms", "max.block.ms")) {
                properties.add(prefix + "configuration." + timeout, () -> 1500);
            }
        }
        properties.add("moduvera.messaging.kafka.immediate-business-boundary-destinations[0]", () -> BOUNDARY);
        properties.add("moduvera.messaging.kafka.relay-enabled", () -> false);
    }

    @Autowired private ImmediatePublication publication;
    @Autowired private PlatformTransactionManager transactions;

    @Test void acknowledgesOnceRejectsRealTransactionsAndPreservesUnknownFailure() throws Exception {
        assertThat(publication).isInstanceOf(KafkaImmediatePublication.class);
        for (String id : List.of("boundary", "internal", "existing", "unsampled", "absent")) {
            invoke(id, () -> publication.publish(message(id)));
        }
        assertThat(SENDS).hasValue(5);
        readBroker(BOUNDARY, 4);
        readBroker(INTERNAL, 1);
        long before = brokerEnd();
        for (boolean readOnly : List.of(false, true)) {
            String id = readOnly ? "readonly-transaction" : "writable-transaction";
            invoke(id, () -> {
                var transaction = new TransactionTemplate(transactions);
                transaction.setReadOnly(readOnly);
                Throwable failure = catchThrowable(() -> transaction.executeWithoutResult(status -> publication.publish(message(id))));
                assertThat(failure).isInstanceOf(ImmediatePublicationInTransactionException.class);
                assertThat(SENDS).hasValue(5);
            });
        }
        assertThat(brokerEnd()).isEqualTo(before).isEqualTo(5);
        KAFKA.stop();
        invoke("broker-failure", () -> {
            Throwable failure = catchThrowable(() -> publication.publish(message("broker-failure")));
            assertThat(failure).isNotNull();
            var receipt = new LinkedHashMap<String, Object>();
            receipt.put("phase", "failure");
            receipt.put("type", failure.getClass().getName());
            receipt.put("sends", SENDS.get());
            receipt.put("broker_outcome", "unknown");
            RECEIPTS.add(receipt);
            LoggerFactory.getLogger("immediate.fixture.final").atError().setCause(failure)
                    .addKeyValue("error.code", "DEP_FIXTURE_BROKER").log("一次发布由调用方处理失败");
        });
        assertThat(SENDS).hasValue(6);
        String evidence = System.getenv("MODUVERA_OBSERVABILITY_EVIDENCE_DIR");
        if (evidence != null) {
            Files.writeString(Path.of(evidence, "immediate-receipts.json"), new ObjectMapper().writeValueAsString(Map.of(
                    "acknowledged", before, "sends", SENDS.get(), "receipts", RECEIPTS)));
        }
    }

    private void invoke(String id, Runnable work) {
        var identity = new ExecutionContext(new TenantId(id.equals("internal") ? "tenant-b" : "tenant-a"),
                new Actor(ActorType.SERVICE, "immediate-publisher"), new Initiator(ActorType.USER, "user-" + id), "corr-" + id);
        Span root = Span.getInvalid();
        Context selected = Context.root();
        if (id.equals("unsampled")) {
            selected = Context.root().with(Span.wrap(SpanContext.create("5".repeat(32), "6".repeat(16),
                    TraceFlags.getDefault(), TraceState.builder().put("vendor", "unsampled").build())));
        } else if (!id.equals("absent")) {
            root = GlobalOpenTelemetry.getTracer("moduvera.immediate.fixture").spanBuilder("fixture.immediate")
                    .setNoParent().startSpan();
            selected = Context.root().with(root);
        }
        try (var business = ExecutionContextHolder.open(identity); var trace = selected.makeCurrent()) {
            var context = Span.current().getSpanContext();
            RECEIPTS.add(Map.of("phase", "caller", "id", id, "trace", context.getTraceId(), "span", context.getSpanId(),
                    "sampled", context.isSampled(), "valid", context.isValid(), "correlation", identity.correlationId()));
            work.run();
            assertThat(ExecutionContextHolder.require()).isSameAs(identity);
            assertThat(Span.current().getSpanContext()).isEqualTo(context);
        } finally {
            root.end();
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    private static long brokerEnd() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            Map<org.apache.kafka.common.TopicPartition, OffsetSpec> requested = new HashMap<>();
            requested.put(new org.apache.kafka.common.TopicPartition(BOUNDARY, 0), OffsetSpec.latest());
            requested.put(new org.apache.kafka.common.TopicPartition(INTERNAL, 0), OffsetSpec.latest());
            return admin.listOffsets(requested).all().get().values().stream().mapToLong(ListOffsetsResult.ListOffsetsResultInfo::offset).sum();
        }
    }

    private static void readBroker(String topic, int expected) throws Exception {
        // The container's console consumer has no Agent; receipts reflect actual Broker bytes/headers.
        var result = KAFKA.execInContainer("kafka-console-consumer", "--bootstrap-server", "localhost:9092",
                "--topic", topic, "--from-beginning", "--max-messages", Integer.toString(expected), "--timeout-ms", "10000",
                "--property", "print.headers=true");
        assertThat(result.getExitCode()).isZero();
        var lines = result.getStdout().lines().toList();
        assertThat(lines).hasSize(expected);
        for (String line : lines) {
            String[] parts = line.split("\t", 2);
            SerializedMessage message = new KafkaMessageMapper().fromSpringMessage(new GenericMessage<>(
                    parts[1].getBytes(StandardCharsets.UTF_8), Map.of("contentType", "application/cloudevents+json")));
            var descriptor = message.descriptor();
            assertThat(new String(message.payload(), StandardCharsets.UTF_8)).contains(PRIVATE_BODY);
            var row = new LinkedHashMap<String, Object>();
            row.put("phase", "broker"); row.put("id", descriptor.id().value()); row.put("headers", parts[0]);
            row.put("creation", descriptor.creationContext() == null ? "absent" : descriptor.creationContext().traceParent());
            row.put("creation_state", descriptor.creationContext() == null || descriptor.creationContext().traceState() == null
                    ? "absent" : descriptor.creationContext().traceState());
            row.put("correlation", descriptor.correlationId()); row.put("tenant", descriptor.tenantId().value());
            row.put("actor", descriptor.actor().subjectId()); row.put("initiator", descriptor.initiator().subjectId());
            row.put("body_size", message.payload().length); RECEIPTS.add(row);
        }
    }

    private static SerializedMessage message(String id) {
        var identity = ExecutionContextHolder.require();
        return SerializedMessage.json(new MessageDescriptor(new MessageId(id), MessageKind.EVENT,
                new MessageType("test.immediate.v1"), URI.create("urn:service:immediate-publisher"),
                new Destination(id.equals("internal") ? INTERNAL : BOUNDARY), Instant.parse("2026-09-01T00:00:00Z"),
                identity.requireTenantId(), identity.actor(), identity.correlationId(), null, identity.initiator(), "key-" + id,
                id.equals("existing") ? EXISTING : null), "{\"value\":\"" + PRIVATE_BODY + "\"}");
    }

    public static final class SendProbe implements ProducerInterceptor<Object, Object> {
        @Override public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) { SENDS.incrementAndGet(); return record; }
        @Override public void onAcknowledgement(RecordMetadata metadata, Exception exception) {}
        @Override public void close() {}
        @Override public void configure(Map<String, ?> configuration) {}
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class FixtureConfiguration {
        @Bean ObjectMapper fixtureObjectMapper() { return new ObjectMapper(); }
    }
}
