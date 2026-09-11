package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.logging.ModuveraEcsStructuredLogFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.mock.env.MockEnvironment;

/** Callback and default-listener orchestration only; real failure is qualified by ImmediatePublicationIT. */
class ImmediateProducerDiagnosticsTest {
    @Test
    @SuppressWarnings("unchecked")
    void onlyOriginalDefaultCallbackIsAdaptedAndLateCallbacksRetainSeparateIdentity() throws Exception {
        var diagnostics = new ImmediateProducerDiagnostics();
        var custom = new LoggingProducerListener<Object, Object>() {};
        assertThat(diagnostics.postProcessAfterInitialization(custom, "producerListener")).isSameAs(custom);
        var unrelatedBean = new LoggingProducerListener<Object, Object>();
        assertThat(diagnostics.postProcessAfterInitialization(unrelatedBean, "other")).isSameAs(unrelatedBean);
        ProducerListener<byte[], byte[]> listener = (ProducerListener<byte[], byte[]>) diagnostics.postProcessAfterInitialization(
                new LoggingProducerListener<byte[], byte[]>(), "producerListener");
        var factory = new DefaultKafkaProducerFactory<byte[], byte[]>(Map.of());
        diagnostics.configure(factory);
        try (var original = new MockProducer<byte[], byte[]>(false, null, new ByteArraySerializer(), new ByteArraySerializer());
                var producer = factory.getPostProcessors().getFirst().apply(original)) {
            var formatter = new ModuveraEcsStructuredLogFormatter(new MockEnvironment());
            var encoded = new ArrayList<String>();
            var events = new ListAppender<ILoggingEvent>() {
                @Override protected void append(ILoggingEvent event) {
                    assertThat(ExecutionContextHolder.current()).isEmpty();
                    encoded.add(formatter.format(event));
                }
            };
            events.start();
            Logger diagnosticLogger = (Logger) LoggerFactory.getLogger("mq.produce.propagating");
            var previousLevel = diagnosticLogger.getLevel();
            diagnosticLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
            Logger nativeLogger = (Logger) LoggerFactory.getLogger(LoggingProducerListener.class);
            diagnosticLogger.addAppender(events); nativeLogger.addAppender(events);
            var callbacks = new AtomicInteger();
            try {
                for (String id : List.of("one", "two")) {
                    var context = ExecutionContext.initiatedBy(new TenantId("tenant-" + id),
                            new Actor(ActorType.SERVICE, "publisher-" + id), "corr-" + id);
                    var record = new ProducerRecord<byte[], byte[]>("output", new byte[0]);
                    try (var identity = ExecutionContextHolder.open(context); var publication = ImmediateProducerDiagnostics.open()) {
                        producer.send(record, (metadata, failure) -> {
                            assertThat(ExecutionContextHolder.current()).isEmpty();
                            callbacks.incrementAndGet();
                            listener.onError(record, metadata, failure);
                        });
                    }
                }
                var task = new FutureTask<Void>(() -> {
                    for (String id : List.of("one", "two")) {
                        original.errorNext(new TimeoutException("callback-private-" + id));
                    }
                    return null;
                });
                Thread.ofPlatform().start(task);
                task.get(5, TimeUnit.SECONDS);
                assertThat(callbacks).hasValue(2);
                assertThat(encoded).hasSize(2);
                for (int index = 0; index < 2; index++) {
                    String id = index == 0 ? "one" : "two";
                    assertThat(encoded.get(index)).contains("corr-" + id, "publisher-" + id, "tenant-" + id,
                            "\"level\":\"DEBUG\"").doesNotContain("callback-private", "stack_trace");
                }
                listener.onError(new ProducerRecord<>("unowned", new byte[0]), null, new TimeoutException("unowned"));
                assertThat(encoded).hasSize(3);
                assertThat(encoded.getLast()).contains("\"level\":\"ERROR\"").doesNotContain("corr-one", "corr-two");
            } finally {
                diagnosticLogger.setLevel(previousLevel);
                diagnosticLogger.detachAppender(events);
                nativeLogger.detachAppender(events);
                events.stop();
            }
        }
    }
}
