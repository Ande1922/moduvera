package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.integration.handler.LoggingHandler;
import org.springframework.integration.context.IntegrationContextUtils;
import org.springframework.integration.config.IntegrationConfigUtils;

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

    @Test
    void attemptsProjectIdentityAndRestoreCompleteContextOnRecoveryFailureAndInterruption() {
        Logger logger = (Logger) LoggerFactory.getLogger("mq.consume");
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        var previous = ExecutionContext.initiatedBy(
                new TenantId("prior"), new Actor(ActorType.SERVICE, "worker"), "corr-prior");
        ContextKey<String> extra = ContextKey.named("inbound-test-extra");
        Context parent = Context.current().with(extra, "transport");
        MDC.put("correlation_id", "prior-mdc");
        MDC.put("unowned", "keep");
        Map<String, String> before = MDC.getCopyOfContextMap();
        try (var execution = ExecutionContextHolder.open(previous); var telemetry = parent.makeCurrent()) {
            for (String outcome : List.of("recovery", "terminal", "exhausted", "error", "interrupted")) {
                var attempts = new AtomicInteger();
                var consumer = new ReliableMessageConsumerFactory(mapper, 2,
                                outcome.equals("interrupted") ? Duration.ofSeconds(1) : Duration.ZERO,
                                Duration.ofSeconds(1))
                        .forContract(contract(), ignored -> {
                            assertThat(Context.current().get(extra)).isEqualTo("transport");
                            assertThat(MDC.get("correlation_id")).isEqualTo("corr-message");
                            assertThat(MDC.get("tenant_id")).isEqualTo("tenant-a");
                            assertThat(MDC.get("actor_id")).isEqualTo("notes-service");
                            assertThat(MDC.get("initiator_id")).isEqualTo("alice");
                            assertThat(ExecutionContextHolder.require().actor().permissions())
                                    .containsExactly("notes:consume");
                            int attempt = attempts.incrementAndGet();
                            switch (outcome) {
                                case "terminal" -> throw new NonRetryableMessageException("private payload");
                                case "error" -> throw new AssertionError("private payload");
                                case "interrupted" -> Thread.currentThread().interrupt();
                                default -> { }
                            }
                            if (!outcome.equals("recovery") || attempt == 1) {
                                throw new IllegalStateException("private payload");
                            }
                        });
                Runnable consume = () -> consumer.accept(mapper.toSpringMessage(message(outcome, "notes.events")));
                if (outcome.equals("recovery")) {
                    consume.run();
                } else {
                    assertThatThrownBy(consume::run).isInstanceOf(
                            outcome.equals("error") ? AssertionError.class : NonRetryableMessageException.class);
                }
                assertThat(ExecutionContextHolder.require()).isSameAs(previous);
                assertThat(Context.current()).isSameAs(parent);
                assertThat(MDC.getCopyOfContextMap()).isEqualTo(before);
                if (outcome.equals("interrupted")) {
                    assertThat(Thread.interrupted()).isTrue();
                }
            }
            assertThat(events.list.stream().filter(event -> event.getLevel() == Level.INFO)).hasSize(7);
            assertThat(events.list.stream().filter(event -> event.getLevel() == Level.WARN)).hasSize(3);
            assertThat(events.list).allSatisfy(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("private payload");
                assertThat(event.getKeyValuePairs()).noneSatisfy(pair ->
                        assertThat(String.valueOf(pair.value)).contains("private payload"));
            });
        } finally {
            Thread.interrupted();
            logger.detachAppender(events);
            events.stop();
            MDC.clear();
        }
    }

    @Test
    void finalLoggerRestoresEachFailureEvenWhenTheApplicationReusesOneException() {
        var original = new NonRetryableMessageException("private payload");
        var consumer = new ReliableMessageConsumerFactory(mapper).forContract(contract(), ignored -> {
            throw original;
        });
        Throwable first = catchThrowable(() -> consumer.accept(
                mapper.toSpringMessage(message("first", "notes.events", "corr-first"))));
        Throwable second = catchThrowable(() -> consumer.accept(
                mapper.toSpringMessage(message("second", "notes.events", "corr-second"))));
        assertThat(first).isNotSameAs(second).hasCause(original);
        assertThat(second).hasCause(original);
        assertThat(original.getSuppressed()).isEmpty();
        var interruptedConsumer = new ReliableMessageConsumerFactory(mapper, 2,
                Duration.ofMillis(1), Duration.ofMillis(1)).forContract(contract(), ignored -> {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("private payload");
                });
        Throwable interrupted = catchThrowable(() -> interruptedConsumer.accept(
                mapper.toSpringMessage(message("interrupted", "notes.events", "corr-interrupted"))));
        assertThat(Thread.interrupted()).isTrue();
        assertThat(interrupted).isInstanceOf(NonRetryableMessageException.class)
                .hasMessage("message retry interrupted");
        var processor = new InboundFailureDiagnostics();
        var originalLogger = new LoggingHandler(LoggingHandler.Level.ERROR);
        assertThat(processor.postProcessAfterInitialization(originalLogger, "customLogger")).isSameAs(originalLogger);
        MessageHandler handler = (MessageHandler) processor.postProcessAfterInitialization(originalLogger,
                IntegrationContextUtils.ERROR_LOGGER_BEAN_NAME + IntegrationConfigUtils.HANDLER_ALIAS_SUFFIX);
        Logger logger = (Logger) LoggerFactory.getLogger("mq.consume.failure");
        var events = new ListAppender<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        events.start();
        logger.addAppender(events);
        var previous = ExecutionContext.initiatedBy(new TenantId("prior"),
                new Actor(ActorType.SERVICE, "worker"), "corr-prior");
        MDC.put("correlation_id", "prior-mdc");
        try (var ignored = ExecutionContextHolder.open(previous)) {
            // Deliberately handle the older failure after the newer one was created.
            handler.handleMessage(new ErrorMessage(first));
            handler.handleMessage(new ErrorMessage(second));
            handler.handleMessage(new ErrorMessage(interrupted));
            assertThat(ExecutionContextHolder.require()).isSameAs(previous);
            assertThat(MDC.get("correlation_id")).isEqualTo("prior-mdc");
            assertThat(events.list).hasSize(3);
            assertThat(events.list).extracting(event -> event.getMDCPropertyMap().get("correlation_id"))
                    .containsExactly("corr-first", "corr-second", "corr-interrupted");
            assertThat(events.list).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getMDCPropertyMap()).containsEntry("actor_id", "notes-service");
                assertThat(event.getFormattedMessage()).isEqualTo("消息处理最终失败");
                assertThat(event.getKeyValuePairs()).noneSatisfy(pair ->
                        assertThat(pair.key).isIn("event.outcome", "duration_ms", "retry.attempt"));
            });
        } finally {
            logger.detachAppender(events);
            events.stop();
            MDC.clear();
        }
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
        return message(messageId, destination, "corr-message");
    }

    private static SerializedMessage message(String messageId, String destination, String correlation) {
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
                        correlation,
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
