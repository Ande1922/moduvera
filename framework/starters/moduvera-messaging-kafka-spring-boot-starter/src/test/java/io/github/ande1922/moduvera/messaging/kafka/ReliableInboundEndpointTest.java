package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
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
import io.github.ande1922.moduvera.message.handler.ApplicationMessageHandler;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;

class ReliableInboundEndpointTest {

    private final KafkaMessageMapper mapper = new KafkaMessageMapper();

    @Test
    void publicFactoryReturnsOnlyTheStandardConsumerSurface() {
        Consumer<Message<byte[]>> consumer = new ReliableMessageConsumerFactory(mapper)
                .forContract(contract(), ignored -> {});

        assertThat(consumer).isInstanceOf(Consumer.class);
        assertThat(consumer.getClass()).hasPackage("io.github.ande1922.moduvera.messaging.kafka");
        assertThat(consumer.getClass().getModifiers() & java.lang.reflect.Modifier.PUBLIC).isZero();
    }

    @Test
    void establishesTrustedContextWithoutStartingABusinessTransaction() {
        var observed = new AtomicReference<ExecutionContext>();
        var consumer = new ReliableMessageConsumerFactory(mapper)
                .forContract(contract(), ignored -> observed.set(ExecutionContextHolder.require()));
        var previous = ExecutionContext.initiatedBy(
                new TenantId("prior"), new Actor(ActorType.SERVICE, "worker"), "corr-prior");

        ExecutionContextHolder.run(previous, () -> {
            consumer.accept(mapper.toSpringMessage(message("message-1", "notes.events")));
            assertThat(ExecutionContextHolder.require()).isSameAs(previous);
        });

        assertThat(observed.get().tenantId()).isEqualTo(new TenantId("tenant-a"));
        assertThat(observed.get().actor().subjectId()).isEqualTo("notes-service");
        assertThat(observed.get().actor().permissions()).containsExactly("notes:consume");
        assertThat(observed.get().initiator()).isEqualTo(new Initiator(ActorType.USER, "alice"));
        assertThat(observed.get().correlationId()).isEqualTo("corr-message");
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void rejectsContractMismatchBeforeCallingTheInboundAdapter() {
        var calls = new AtomicInteger();
        var consumer = new ReliableMessageConsumerFactory(mapper)
                .forContract(contract(), ignored -> calls.incrementAndGet());

        assertThatThrownBy(() -> consumer.accept(
                        mapper.toSpringMessage(message("wrong", "inventory.events"))))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThat(calls).hasValue(0);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void retriesApplicationFailuresWithContextClearedBetweenAttempts() {
        var attempts = new AtomicInteger();
        var consumer = new ReliableMessageConsumerFactory(
                        mapper, 3, Duration.ZERO, Duration.ZERO)
                .forContract(contract(), ignored -> {
                    assertThat(ExecutionContextHolder.require().actor().subjectId())
                            .isEqualTo("notes-service");
                    if (attempts.incrementAndGet() < 3) {
                        throw new IllegalStateException("temporarily unavailable");
                    }
                });

        consumer.accept(mapper.toSpringMessage(message("retry", "notes.events")));

        assertThat(attempts).hasValue(3);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void terminalFailuresAreNotRetriedAndExhaustionPropagates() {
        var terminalAttempts = new AtomicInteger();
        var retryableAttempts = new AtomicInteger();
        var factory = new ReliableMessageConsumerFactory(
                mapper, 2, Duration.ZERO, Duration.ZERO);
        var terminal = factory.forContract(contract(), ignored -> {
            terminalAttempts.incrementAndGet();
            throw new NonRetryableMessageException("invalid payload");
        });
        var retryable = factory.forContract(contract(), ignored -> {
            retryableAttempts.incrementAndGet();
            throw new IllegalStateException("database unavailable");
        });

        assertThatThrownBy(() -> terminal.accept(mapper.toSpringMessage(message("terminal", "notes.events"))))
                .isInstanceOf(NonRetryableMessageException.class)
                .hasMessage("invalid payload");
        assertThatThrownBy(() -> retryable.accept(mapper.toSpringMessage(message("exhausted", "notes.events"))))
                .isInstanceOf(NonRetryableMessageException.class)
                .hasMessage("message handling exhausted 2 attempts")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(terminalAttempts).hasValue(1);
        assertThat(retryableAttempts).hasValue(2);
    }

    @Test
    void preparationFailureOnAMissRunsOutsideTheInboxTransactionAndLeavesNoRecord() {
        var inboxRepository = new RecordingInboxRepository();
        var transactions = new RecordingTransactionBoundary();
        var preparationCalls = new AtomicInteger();
        var preparationObservedTransaction = new AtomicReference<Boolean>();
        var businessCalls = new AtomicInteger();
        var preparationFailure = new IllegalStateException("preparation unavailable");
        var inbox = new InboxTemplate(
                "prepared-handler",
                inboxRepository,
                transactions,
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
        ApplicationMessageHandler<byte[]> handler = new PreparedApplicationHandler(
                inbox,
                payload -> {
                    preparationCalls.incrementAndGet();
                    preparationObservedTransaction.set(transactions.active);
                    throw preparationFailure;
                },
                businessCalls::incrementAndGet);
        var consumer = new ReliableMessageConsumerFactory(mapper)
                .forContract(contract(), serialized -> handler.handle(
                        serialized.payload(), serialized.descriptor().id()));

        assertThatThrownBy(() -> consumer.accept(
                        mapper.toSpringMessage(message("preparation-failure", "notes.events"))))
                .isInstanceOf(NonRetryableMessageException.class)
                .hasMessage("message handling exhausted 1 attempts")
                .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(preparationFailure));

        assertThat(inboxRepository.prechecks).isOne();
        assertThat(preparationCalls).hasValue(1);
        assertThat(preparationObservedTransaction).hasValue(false);
        assertThat(transactions.calls).isZero();
        assertThat(inboxRepository.starts).isZero();
        assertThat(businessCalls).hasValue(0);
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

    private static SerializedMessage message(String messageId, String destination) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId(messageId),
                        MessageKind.EVENT,
                        new MessageType("io.github.ande1922.moduvera.example.notes.created.v1"),
                        URI.create("urn:service:notes"),
                        new Destination(destination),
                        Instant.parse("2026-08-30T00:00:00Z"),
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "wire-producer", Set.of("wire:permission")),
                        "corr-message",
                        null,
                        new Initiator(ActorType.USER, "alice"),
                        "tenant-a:1"),
                "{}");
    }

    private record PreparedApplicationHandler(
            InboxTemplate inbox, Consumer<byte[]> preparation, Runnable businessWork)
            implements ApplicationMessageHandler<byte[]> {

        @Override
        public void handle(byte[] payload, MessageId messageId) {
            if (inbox.isProcessed(messageId)) {
                return;
            }
            preparation.accept(payload);
            inbox.handle(messageId, businessWork);
        }
    }

    private static final class RecordingInboxRepository implements InboxRepository {
        private int prechecks;
        private int starts;

        @Override
        public boolean isProcessed(TenantId tenantId, String consumerId, MessageId messageId) {
            prechecks++;
            return false;
        }

        @Override
        public boolean tryStart(
                TenantId tenantId,
                String consumerId,
                MessageId messageId,
                Instant processedAt) {
            starts++;
            return true;
        }
    }

    private static final class RecordingTransactionBoundary implements TransactionBoundary {
        private boolean active;
        private int calls;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            calls++;
            active = true;
            try {
                return work.get();
            } finally {
                active = false;
            }
        }
    }
}
