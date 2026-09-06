package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
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
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ReliableInboundEndpointTest {

    private final KafkaMessageMapper mapper = new KafkaMessageMapper();
    private final ReliableMessageConsumerFactory factory = new ReliableMessageConsumerFactory(
            mapper,
            acceptingInboxRepository(),
            new DirectTransactionBoundary(),
            Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void invokesTheBoundHandlerInsideTrustedInboxAndSkipsDuplicates() {
        Set<MessageId> admitted = new HashSet<>();
        var duplicateAwareFactory = new ReliableMessageConsumerFactory(
                mapper,
                recordingInboxRepository(admitted),
                new DirectTransactionBoundary(),
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
        AtomicInteger handled = new AtomicInteger();
        var consumer = duplicateAwareFactory.forConsumer("notes-audit", contract(), message -> {
            assertThat(ExecutionContextHolder.require().tenantId()).isEqualTo(new TenantId("tenant-a"));
            assertThat(ExecutionContextHolder.require().actor().subjectId()).isEqualTo("notes-service");
            handled.incrementAndGet();
        });
        var inbound = mapper.toSpringMessage(message("msg-once", "notes.events"));

        assertThat(consumer).isInstanceOf(Consumer.class);
        var previous = priorWorkerContext();
        ExecutionContextHolder.run(previous, () -> {
            assertThat(consumer.handle(inbound))
                    .isEqualTo(io.github.ande1922.moduvera.message.inbox.InboxOutcome.APPLIED);
            assertThat(ExecutionContextHolder.require()).isSameAs(previous);
            assertThat(consumer.handle(inbound))
                    .isEqualTo(io.github.ande1922.moduvera.message.inbox.InboxOutcome.DUPLICATE);
            assertThat(ExecutionContextHolder.require()).isSameAs(previous);
        });

        assertThat(handled).hasValue(1);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void establishesAndAlwaysClearsTheTrustedMessageContext() {
        AtomicInteger handled = new AtomicInteger();
        var successful = factory.forConsumer("notes-audit", contract(), ignored -> {
            assertThat(ExecutionContextHolder.require().tenantId()).isEqualTo(new TenantId("tenant-a"));
            assertThat(ExecutionContextHolder.require().actor().subjectId()).isEqualTo("notes-service");
            assertThat(ExecutionContextHolder.require().actor().permissions())
                    .containsExactly("notes:consume");
            handled.incrementAndGet();
        });
        var failing = factory.forConsumer("notes-audit", contract(), ignored -> {
            throw new IllegalStateException("boom");
        });

        var previous = priorWorkerContext();
        ExecutionContextHolder.run(previous, () -> {
            successful.handle(mapper.toSpringMessage(message("msg-success", "notes.events")));
            assertThat(ExecutionContextHolder.require()).isSameAs(previous);

            assertThatThrownBy(() -> failing.handle(
                            mapper.toSpringMessage(message("msg-failure", "notes.events"))))
                    .isInstanceOf(NonRetryableMessageException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(ExecutionContextHolder.require()).isSameAs(previous);
        });
        assertThat(ExecutionContextHolder.current()).isEmpty();
        assertThat(handled).hasValue(1);
    }

    @Test
    void rejectsAnUnexpectedKindTypeSourceOrDestinationBeforeTheBusinessHandler() {
        AtomicInteger handled = new AtomicInteger();
        var consumer = factory.forConsumer(
                "notes-audit", contract(), ignored -> handled.incrementAndGet());

        assertThatThrownBy(() -> consumer.handle(
                        mapper.toSpringMessage(message("msg-route", "inventory.events"))))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> consumer.handle(
                        mapper.toSpringMessage(message(
                                "msg-type",
                                MessageKind.EVENT,
                                "io.github.ande1922.moduvera.example.notes.created.v2",
                                URI.create("urn:service:notes"),
                                "notes.events",
                                "tenant-a"))))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> consumer.handle(
                        mapper.toSpringMessage(message(
                                "msg-source",
                                MessageKind.EVENT,
                                "io.github.ande1922.moduvera.example.notes.created.v1",
                                URI.create("urn:service:unknown"),
                                "notes.events",
                                "tenant-a"))))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> consumer.handle(
                        mapper.toSpringMessage(message(
                                "msg-kind",
                                MessageKind.ASYNC_COMMAND,
                                "io.github.ande1922.moduvera.example.notes.created.v1",
                                URI.create("urn:service:notes"),
                                "notes.events",
                                "tenant-a"))))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThat(handled).hasValue(0);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void retriesTransientFailuresWithinTheConfiguredBound() {
        var retryingFactory = new ReliableMessageConsumerFactory(
                mapper,
                acceptingInboxRepository(),
                new DirectTransactionBoundary(),
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC),
                3,
                Duration.ofNanos(1),
                Duration.ofNanos(1));
        AtomicInteger attempts = new AtomicInteger();
        var consumer = retryingFactory.forConsumer("notes-audit", contract(), ignored -> {
            assertThat(ExecutionContextHolder.require()).isEqualTo(new ExecutionContext(
                    new TenantId("tenant-a"),
                    contract().executionActor(),
                    new Initiator(ActorType.USER, "alice"),
                    "corr-1"));
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("temporary");
            }
        });

        var previous = priorWorkerContext();
        ExecutionContextHolder.run(previous, () -> {
            consumer.handle(mapper.toSpringMessage(message("msg-retry", "notes.events")));
            assertThat(ExecutionContextHolder.require()).isSameAs(previous);
        });

        assertThat(attempts).hasValue(3);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void doesNotRetryAHandlerClassifiedAsNonRetryable() {
        var retryingFactory = new ReliableMessageConsumerFactory(
                mapper,
                acceptingInboxRepository(),
                new DirectTransactionBoundary(),
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC),
                3,
                Duration.ofNanos(1),
                Duration.ofNanos(1));
        AtomicInteger attempts = new AtomicInteger();
        var consumer = retryingFactory.forConsumer("notes-audit", contract(), ignored -> {
            attempts.incrementAndGet();
            throw new NonRetryableMessageException("invalid payload");
        });

        assertThatThrownBy(() -> consumer.handle(
                        mapper.toSpringMessage(message("msg-terminal", "notes.events"))))
                .isInstanceOf(NonRetryableMessageException.class)
                .hasMessage("invalid payload");

        assertThat(attempts).hasValue(1);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void isolatesAlternatingMessagesOnReusedAndVirtualThreads() throws Exception {
        var consumer = factory.forConsumer("notes-audit", contract(), message -> {
            assertThat(ExecutionContextHolder.require().tenantId())
                    .isEqualTo(message.descriptor().tenantId());
            assertThat(ExecutionContextHolder.require().correlationId()).isEqualTo("corr-1");
        });
        try (var reused = Executors.newFixedThreadPool(1)) {
            var tenantA = reused.submit(() -> consumer.handle(
                    mapper.toSpringMessage(message("msg-thread-a", "notes.events", "tenant-a"))));
            var tenantB = reused.submit(() -> consumer.handle(
                    mapper.toSpringMessage(message("msg-thread-b", "notes.events", "tenant-b"))));
            tenantA.get();
            tenantB.get();
            assertThat(reused.submit(ExecutionContextHolder::current).get()).isEmpty();
        }

        try (var virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = virtual.submit(() -> {
                assertThat(Thread.currentThread().isVirtual()).isTrue();
                return consumer.handle(
                        mapper.toSpringMessage(message("msg-virtual", "notes.events", "tenant-a")));
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

    private static ExecutionContext priorWorkerContext() {
        return ExecutionContext.initiatedBy(
                ExecutionScope.platform(),
                new Actor(ActorType.SERVICE, "listener-worker", Set.of("listener:local")),
                "corr-worker-before-message");
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

    private static InboxRepository acceptingInboxRepository() {
        return new InboxRepository() {
            @Override
            public boolean isProcessed(
                    TenantId tenantId, String consumerId, MessageId messageId) {
                return false;
            }

            @Override
            public boolean tryStart(
                    TenantId tenantId,
                    String consumerId,
                    MessageId messageId,
                    Instant processedAt) {
                return true;
            }
        };
    }

    private static InboxRepository recordingInboxRepository(Set<MessageId> admitted) {
        return new InboxRepository() {
            @Override
            public boolean isProcessed(
                    TenantId tenantId, String consumerId, MessageId messageId) {
                return admitted.contains(messageId);
            }

            @Override
            public boolean tryStart(
                    TenantId tenantId,
                    String consumerId,
                    MessageId messageId,
                    Instant processedAt) {
                return admitted.add(messageId);
            }
        };
    }

    private static final class DirectTransactionBoundary implements TransactionBoundary {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }
}
