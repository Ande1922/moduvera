package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ReliableMessageConsumerTest {

    private final KafkaMessageMapper mapper = new KafkaMessageMapper();
    private final ReliableMessageConsumerFactory factory = new ReliableMessageConsumerFactory(
            mapper,
            (tenantId, consumerId, messageId, processedAt) -> true,
            new DirectTransactionBoundary(),
            Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void establishesAndAlwaysClearsTheTrustedMessageContext() {
        var consumer = factory.forConsumer("notes-audit", contract());
        AtomicInteger handled = new AtomicInteger();

        consumer.handle(mapper.toSpringMessage(message("msg-success", "notes.events")), ignored -> {
            assertThat(ExecutionContextHolder.require().tenantId()).isEqualTo(new TenantId("tenant-a"));
            assertThat(ExecutionContextHolder.require().actor().subjectId()).isEqualTo("notes-service");
            assertThat(ExecutionContextHolder.require().actor().permissions())
                    .containsExactly("notes:consume");
            handled.incrementAndGet();
        });
        assertThat(ExecutionContextHolder.current()).isEmpty();

        assertThatThrownBy(() -> consumer.handle(
                        mapper.toSpringMessage(message("msg-failure", "notes.events")),
                        ignored -> {
                            throw new IllegalStateException("boom");
                        }))
                .isInstanceOf(NonRetryableMessageException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(ExecutionContextHolder.current()).isEmpty();
        assertThat(handled).hasValue(1);
    }

    @Test
    void rejectsAnUnexpectedKindTypeSourceOrDestinationBeforeTheBusinessHandler() {
        var consumer = factory.forConsumer("notes-audit", contract());
        AtomicInteger handled = new AtomicInteger();

        assertThatThrownBy(() -> consumer.handle(
                        mapper.toSpringMessage(message("msg-route", "inventory.events")),
                        ignored -> handled.incrementAndGet()))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> consumer.handle(
                        mapper.toSpringMessage(message(
                                "msg-type",
                                MessageKind.EVENT,
                                "io.github.ande1922.moduvera.example.notes.created.v2",
                                URI.create("urn:service:notes"),
                                "notes.events",
                                "tenant-a")),
                        ignored -> handled.incrementAndGet()))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> consumer.handle(
                        mapper.toSpringMessage(message(
                                "msg-source",
                                MessageKind.EVENT,
                                "io.github.ande1922.moduvera.example.notes.created.v1",
                                URI.create("urn:service:unknown"),
                                "notes.events",
                                "tenant-a")),
                        ignored -> handled.incrementAndGet()))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> consumer.handle(
                        mapper.toSpringMessage(message(
                                "msg-kind",
                                MessageKind.ASYNC_COMMAND,
                                "io.github.ande1922.moduvera.example.notes.created.v1",
                                URI.create("urn:service:notes"),
                                "notes.events",
                                "tenant-a")),
                        ignored -> handled.incrementAndGet()))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThat(handled).hasValue(0);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void retriesTransientFailuresWithinTheConfiguredBound() {
        var retryingFactory = new ReliableMessageConsumerFactory(
                mapper,
                (tenant, consumer, messageId, processedAt) -> true,
                new DirectTransactionBoundary(),
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC),
                3,
                Duration.ofNanos(1),
                Duration.ofNanos(1));
        var consumer = retryingFactory.forConsumer("notes-audit", contract());
        AtomicInteger attempts = new AtomicInteger();

        consumer.handle(mapper.toSpringMessage(message("msg-retry", "notes.events")), ignored -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("temporary");
            }
        });

        assertThat(attempts).hasValue(3);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void isolatesAlternatingMessagesOnReusedAndVirtualThreads() throws Exception {
        var consumer = factory.forConsumer("notes-audit", contract());
        try (var reused = Executors.newFixedThreadPool(1)) {
            var tenantA = reused.submit(() -> consumer.handle(
                    mapper.toSpringMessage(message("msg-thread-a", "notes.events", "tenant-a")),
                    ignored -> assertThat(ExecutionContextHolder.require().tenantId())
                            .isEqualTo(new TenantId("tenant-a"))));
            var tenantB = reused.submit(() -> consumer.handle(
                    mapper.toSpringMessage(message("msg-thread-b", "notes.events", "tenant-b")),
                    ignored -> assertThat(ExecutionContextHolder.require().tenantId())
                            .isEqualTo(new TenantId("tenant-b"))));
            tenantA.get();
            tenantB.get();
            assertThat(reused.submit(ExecutionContextHolder::current).get()).isEmpty();
        }

        try (var virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = virtual.submit(() -> {
                assertThat(Thread.currentThread().isVirtual()).isTrue();
                return consumer.handle(
                        mapper.toSpringMessage(message("msg-virtual", "notes.events", "tenant-a")),
                        ignored -> assertThat(ExecutionContextHolder.require().correlationId())
                                .isEqualTo("corr-1"));
            });
            assertThat(result.get()).isEqualTo(io.github.ande1922.moduvera.message.inbox.InboxOutcome.APPLIED);
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    private static InboundMessageContract contract() {
        return new InboundMessageContract(
                MessageKind.EVENT,
                new MessageType("io.github.ande1922.moduvera.example.notes.created.v1"),
                URI.create("urn:service:notes"),
                new Destination("notes.events"),
                new Actor(ActorType.SERVICE, "notes-service", Set.of("notes:consume")));
    }

    private static SerializedMessage message(String id, String destination) {
        return message(id, destination, "tenant-a");
    }

    private static SerializedMessage message(String id, String destination, String tenantId) {
        return message(
                id,
                MessageKind.EVENT,
                "io.github.ande1922.moduvera.example.notes.created.v1",
                URI.create("urn:service:notes"),
                destination,
                tenantId);
    }

    private static SerializedMessage message(
            String id,
            MessageKind kind,
            String type,
            URI source,
            String destination,
            String tenantId) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId(id),
                        kind,
                        new MessageType(type),
                        source,
                        new Destination(destination),
                        Instant.parse("2026-08-30T00:00:00Z"),
                        new TenantId(tenantId),
                        new Actor(ActorType.SERVICE, "notes-service", Set.of("sender:claimed")),
                        "corr-1",
                        null,
                        new Initiator(ActorType.USER, "alice"),
                        tenantId + ":note-1"),
                "{}");
    }

    private static final class DirectTransactionBoundary implements TransactionBoundary {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }
}
