package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.env.MockEnvironment;

/** Format and publication orchestration; real ACK/transaction evidence is in ImmediatePublicationIT. */
class KafkaImmediateDiagnosticsTest {
    @Test void capturesOnlyAnAvailableCurrentCreationAndPreservesSuppliedCreation() {
        ContextKey<String> key = ContextKey.named("immediate-extra");
        var received = new AtomicReference<SerializedMessage>();
        var publication = new KafkaImmediatePublication(received::set);
        SerializedMessage original = message(null);
        try (var absent = Context.root().makeCurrent()) {
            publication.publish(original);
            assertThat(received.get()).isSameAs(original);
        }
        for (TraceFlags flags : List.of(TraceFlags.getSampled(), TraceFlags.getDefault())) {
            var span = SpanContext.create("1".repeat(32), "2".repeat(16), flags,
                    TraceState.builder().put("vendor", "value").build());
            Context current = Context.root().with(Span.wrap(span)).with(key, "preserved");
            try (var scope = current.makeCurrent()) {
                publication.publish(original);
                SerializedMessage sent = received.get();
                assertThat(sent.descriptor().creationContext()).isEqualTo(new TraceContextCarrier(
                        "00-" + span.getTraceId() + "-" + span.getSpanId() + "-" + flags.asHex(), "vendor=value"));
                assertThat(sent.payload()).containsExactly(original.payload());
                assertThat(sent.contentType()).isEqualTo(original.contentType());
                assertThat(sent.descriptor().id()).isEqualTo(original.descriptor().id());
                assertThat(sent.descriptor().correlationId()).isEqualTo(original.descriptor().correlationId());
                assertThat(original.descriptor().creationContext()).isNull();
                assertThat(Context.current()).isSameAs(current);
                assertThat(Context.current().get(key)).isEqualTo("preserved");
                SerializedMessage existing = message(new TraceContextCarrier(
                        "00-" + "3".repeat(32) + "-" + "4".repeat(16) + "-01", null));
                publication.publish(existing);
                assertThat(received.get()).isSameAs(existing);
            }
        }
    }

    @Test void resultsFollowBoundaryAndActualReturnWhileOriginalFailuresAndOuterStateSurvive() {
        Logger logger = (Logger) LoggerFactory.getLogger("mq.produce");
        var formatter = new ModuveraEcsStructuredLogFormatter(new MockEnvironment());
        var encoded = new ArrayList<String>();
        var events = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                encoded.add(formatter.format(event));
                super.append(event);
            }
        };
        events.start();
        logger.addAppender(events);
        var identity = ExecutionContext.initiatedBy(new TenantId("tenant-one"),
                new Actor(ActorType.SERVICE, "publisher"), "correlation-current");
        MDC.put("correlation_id", "outer-mdc");
        try (var scope = ExecutionContextHolder.open(identity)) {
            new KafkaImmediatePublication(ignored -> {}).publish(message(null));
            assertThat(encoded).isEmpty();
            new KafkaImmediatePublication(ignored -> assertThat(encoded).isEmpty(), Set.of("destination"))
                    .publish(message(null));
            assertThat(encoded).hasSize(1);
            for (Throwable failure : List.of(new IllegalStateException("immediate-private-cause"),
                    new AssertionError("immediate-private-cause"))) {
                int before = encoded.size();
                var publication = new KafkaImmediatePublication(ignored -> {
                    assertThat(encoded).hasSize(before);
                    if (failure instanceof Error error) { throw error; }
                    throw (RuntimeException) failure;
                });
                assertThat(catchThrowable(() -> publication.publish(message(null)))).isSameAs(failure);
                assertThat(encoded).hasSize(before + 1);
            }
            assertThat(encoded.getFirst()).contains("\"outcome\":\"success\"");
            assertThat(encoded.subList(1, 3)).allSatisfy(json -> assertThat(json)
                    .contains("\"outcome\":\"failure\"").contains("\"type\":")
                    .doesNotContain("stack_trace", "immediate-private-cause"));
            assertThat(encoded).allSatisfy(json -> assertThat(json)
                    .contains("\"level\":\"INFO\"", "\"duration_ms\":", "correlation-current", "publisher")
                    .doesNotContain("immediate-private-body", "retry", "trace_id"));
            assertThat(ExecutionContextHolder.require()).isSameAs(identity);
            assertThat(MDC.get("correlation_id")).isEqualTo("outer-mdc");
        } finally {
            MDC.remove("correlation_id");
            logger.detachAppender(events);
            events.stop();
        }
    }

    private static SerializedMessage message(TraceContextCarrier creation) {
        return SerializedMessage.json(new MessageDescriptor(new MessageId("immediate-one"), MessageKind.EVENT,
                new MessageType("test.immediate.v1"), URI.create("urn:service:publisher"),
                new Destination("destination"), Instant.parse("2026-09-01T00:00:00Z"),
                new TenantId("tenant-envelope"), new Actor(ActorType.SERVICE, "publisher"), "correlation-envelope",
                null, new Initiator(ActorType.SYSTEM, "fixture"), "partition-key", creation),
                "{\"value\":\"immediate-private-body\"}");
    }
}
