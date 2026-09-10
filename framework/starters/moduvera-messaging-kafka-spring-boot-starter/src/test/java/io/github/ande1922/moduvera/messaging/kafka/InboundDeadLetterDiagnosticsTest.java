package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.binder.kafka.KafkaMessageChannelBinder;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.kafka.inbound.KafkaMessageDrivenChannelAdapter;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.messaging.support.GenericMessage;

/** Callback/assembly orchestration only; InboundCausalityIT supplies actual Kafka semantics. */
class InboundDeadLetterDiagnosticsTest {
    @Test void nativeLogAdaptationRequiresTheSameFailureAndStopsWithItsBean() {
        Logger nativeLogger = (Logger) LoggerFactory.getLogger("org.springframework.kafka.support.LoggingProducerListener");
        var nativeEvents = new ListAppender<ILoggingEvent>();
        nativeEvents.start();
        nativeLogger.addAppender(nativeEvents);
        var filter = new InboundRecoveryLogFilter();
        filter.afterPropertiesSet();
        try (var fixture = new Fixture(false)) {
            fixture.dispatch(record -> {
                fixture.producer.send(record, (metadata, failure) -> {
                    nativeLogger.error("propagating send failure", failure);
                    nativeLogger.error("unrelated failure", new IllegalStateException("other"));
                });
                fixture.original.errorNext(new TimeoutException("send failed"));
            });
            assertThat(nativeEvents.list).extracting(ILoggingEvent::getLevel)
                    .containsExactly(ch.qos.logback.classic.Level.INFO, ch.qos.logback.classic.Level.ERROR);
            fixture.assertCanonical(false);
            assertThat(fixture.events.list.stream().filter(event -> event.getLoggerName().equals("mq.consume.recovery.failure")))
                    .hasSize(1);
            filter.destroy();
            nativeLogger.error("outside recovery", new TimeoutException("outside"));
            assertThat(nativeEvents.list.getLast().getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
        } finally {
            filter.destroy();
            nativeLogger.detachAppender(nativeEvents);
            nativeEvents.stop();
        }
    }

    @Test void acknowledgementFailureAndLateCallbacksCompleteOnlyOnce() {
        for (String result : new String[] {"ack", "failure", "late", "unrelated", "none"}) {
            try (var fixture = new Fixture(false)) {
                var callbacks = new AtomicInteger();
                fixture.dispatch(record -> {
                    if (result.equals("none")) {
                        return;
                    }
                    var outgoing = result.equals("unrelated") ? new ProducerRecord<byte[], byte[]>("other", new byte[0]) : record;
                    fixture.producer.send(outgoing, (metadata, failure) -> {
                        assertThat(ExecutionContextHolder.current()).isEmpty();
                        callbacks.incrementAndGet();
                    });
                    if (result.equals("failure")) {
                        fixture.original.errorNext(new TimeoutException("fixture callback failure"));
                    } else if (!result.equals("late")) {
                        fixture.original.completeNext();
                    }
                });
                if (result.equals("late")) {
                    fixture.original.completeNext();
                }
                fixture.assertCanonical(result.equals("ack"));
                assertThat(fixture.events.list.stream().filter(event -> event.getLoggerName().equals("mq.consume.recovery.failure")))
                        .hasSize(result.equals("failure") ? 1 : 0);
                assertThat(callbacks).hasValue(result.equals("none") ? 0 : 1);
            }
        }
    }

    @Test void transactionAcknowledgementWaitsForCommitAndAbortNeverClaimsDeadLetter() {
        for (boolean commit : new boolean[] {true, false}) {
            try (var fixture = new Fixture(true)) {
                fixture.producer.initTransactions();
                fixture.producer.beginTransaction();
                fixture.dispatch(record -> {
                    fixture.producer.send(record, null);
                    fixture.original.completeNext();
                });
                assertThat(fixture.events.list).isEmpty();
                if (commit) {
                    fixture.producer.commitTransaction();
                } else {
                    fixture.producer.abortTransaction();
                }
                fixture.assertCanonical(commit);
            }
        }
    }

    @Test void deliveryScopeRestoresEvenWhenOriginalErrorEscapes() {
        try (var fixture = new Fixture(false)) {
            fixture.input.unsubscribe(fixture.handler);
            Error original = new AssertionError("fixture Error");
            fixture.input.subscribe(message -> { throw original; });
            Throwable thrown = catchThrowable(() -> fixture.adapter.getOutputChannel().send(new GenericMessage<>("input")));
            assertThat(thrown).isSameAs(original);
            assertThat(InboundDeadLetterDiagnostics.isDeliveryObserved()).isFalse();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger("mq.consume");
        private final ListAppender<ILoggingEvent> events = new ListAppender<>();
        private final MockProducer<byte[], byte[]> original = new MockProducer<>(false, null,
                new ByteArraySerializer(), new ByteArraySerializer());
        private final Producer<byte[], byte[]> producer;
        private final DirectChannel input = new DirectChannel();
        private final DirectChannel errors = new DirectChannel();
        private final KafkaMessageDrivenChannelAdapter<byte[], byte[]> adapter;
        private final org.springframework.messaging.MessageHandler handler;

        @SuppressWarnings("unchecked")
        private Fixture(boolean transactional) {
            events.start();
            logger.addAppender(events);
            var factory = new DefaultKafkaProducerFactory<byte[], byte[]>(Map.of());
            if (transactional) {
                factory.setTransactionIdPrefix("diagnostic-test");
            }
            new InboundDeadLetterDiagnostics().configure(factory);
            producer = factory.getPostProcessors().getFirst().apply(original);
            AbstractMessageListenerContainer<byte[], byte[]> container = mock(AbstractMessageListenerContainer.class);
            when(container.getContainerProperties()).thenReturn(new org.springframework.kafka.listener.ContainerProperties("input"));
            adapter = new KafkaMessageDrivenChannelAdapter<>(container);
            adapter.setOutputChannel(input);
            adapter.setErrorChannel(errors);
            InboundDeadLetterDiagnostics.configureEndpoint(adapter);
            var identity = ExecutionContext.initiatedBy(new TenantId("tenant"),
                    new Actor(ActorType.SERVICE, "consumer"), "correlation");
            var message = SerializedMessage.json(new MessageDescriptor(new MessageId("message"), MessageKind.EVENT,
                    new MessageType("fixture.v1"), URI.create("urn:fixture"), new Destination("input"), Instant.EPOCH,
                    new TenantId("tenant"), identity.actor(), "correlation", null, identity.initiator(), "key"), "{}");
            handler = ignored -> {
                try (var scope = ExecutionContextHolder.open(identity);
                        var observation = new InboundAttemptObservation(message, 2, 1, 1)) {
                    var failure = new NonRetryableMessageException("fixture terminal");
                    observation.failed(failure, false);
                    InboundFailureDiagnostics.retain(failure, observation);
                    throw failure;
                }
            };
            input.subscribe(handler);
        }

        private void dispatch(Consumer<ProducerRecord<byte[], byte[]>> send) {
            Throwable failure = catchThrowable(() -> adapter.getOutputChannel().send(new GenericMessage<>("input")));
            assertThat(InboundDeadLetterDiagnostics.isDeliveryObserved()).isFalse();
            assertThat(events.list).isEmpty();
            var headers = new RecordHeaders();
            headers.add(KafkaMessageChannelBinder.X_ORIGINAL_TOPIC, "input".getBytes(StandardCharsets.UTF_8));
            headers.add(KafkaMessageChannelBinder.X_ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(0).array());
            headers.add(KafkaMessageChannelBinder.X_ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(3).array());
            errors.subscribe(ignored -> send.accept(new ProducerRecord<byte[], byte[]>("dead", 0, (byte[]) null, new byte[0], headers)));
            adapter.getErrorChannel().send(new ErrorMessage(failure,
                    Map.of(IntegrationMessageHeaderAccessor.SOURCE_DATA,
                            new ConsumerRecord<>("input", 0, 3, new byte[0], new byte[0]))));
        }

        private void assertCanonical(boolean deadLetter) {
            var canonical = events.list.stream().filter(event -> event.getLoggerName().equals("mq.consume")).toList();
            assertThat(canonical).hasSize(1);
            var disposition = canonical.getFirst().getKeyValuePairs().stream()
                    .filter(pair -> pair.key.equals("disposition")).map(pair -> pair.value).toList();
            if (deadLetter) {
                assertThat(disposition).containsExactly("dead_letter");
            } else {
                assertThat(disposition).isEmpty();
            }
        }

        @Override
        public void close() {
            producer.close();
            logger.detachAppender(events);
            events.stop();
        }
    }
}
