package io.github.ande1922.moduvera.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.Initiator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class DiagnosticLogProjectionTest {
    @Test void readsLatestIdentityAndUsesServerTraceOnlyWhenCurrentSpanIsAbsent() {
        var server = span("1234567890123456");
        DiagnosticLogSnapshot captured;
        try (var ignored = Context.root().with(Span.wrap(server)).makeCurrent()) {
            captured = DiagnosticLogSnapshot.captureWithoutIdentity("request");
        }
        var latest = new AtomicReference<DiagnosticLogSnapshot>();
        try (var ignored = DiagnosticLogProjection.open(latest::get)) {
            assertThat(TrustedLogContext.currentFields()).isEmpty();
            latest.set(captured);
            assertThat(TrustedLogContext.currentFields()).containsEntry("span_id", server.getSpanId());
            assertThat(Span.current().getSpanContext().isValid()).isFalse();
            latest.set(captured.withIdentity(identity(ActorType.USER, "request-user")));
            assertThat(TrustedLogContext.currentFields()).containsEntry("user_id", "request-user");
            assertThat(ExecutionContextHolder.current()).isEmpty();
            var child = span("abcdef1234567890");
            try (var childScope = Context.root().with(Span.wrap(child)).makeCurrent()) {
                assertThat(TrustedLogContext.currentFields()).containsEntry("span_id", child.getSpanId());
                try (var completion = captured.openScope()) {
                    assertThat(TrustedLogContext.currentFields()).containsEntry("span_id", server.getSpanId())
                            .doesNotContainKey("actor_id");
                }
                assertThat(Span.current().getSpanContext()).isEqualTo(child);
            }
        }
        assertThat(TrustedLogContext.currentFields()).isEmpty();
    }

    @Test void activeExecutionReplacesFallbackIncludingAbsentUserAndTenant() {
        var request = DiagnosticLogSnapshot.captureWithoutIdentity("request")
                .withIdentity(identity(ActorType.USER, "request-user"));
        try (var ignored = DiagnosticLogProjection.open(() -> request);
                var execution = ExecutionContextHolder.open(identity(ActorType.SERVICE, "service"))) {
            assertThat(TrustedLogContext.currentFields()).containsEntry("actor_id", "service")
                    .containsEntry("correlation_id", "execution").doesNotContainKey("user_id");
        }
    }

    @Test void nestedWrongThreadAndReusedThreadScopesRestoreWithoutRetainingRequests() throws Exception {
        MDC.put("outer-key", "retained");
        var outer = DiagnosticLogProjection.open(() -> DiagnosticLogSnapshot.captureWithoutIdentity("outer"));
        var inner = DiagnosticLogProjection.open(() -> DiagnosticLogSnapshot.captureWithoutIdentity("inner"));
        try {
            assertThatThrownBy(outer::close).isInstanceOf(IllegalStateException.class);
            var other = new FutureTask<>(() -> {
                assertThatThrownBy(inner::close).isInstanceOf(IllegalStateException.class);
                assertThat(TrustedLogContext.currentFields()).isEmpty();
                try (var ignored = DiagnosticLogProjection.open(() -> DiagnosticLogSnapshot.captureWithoutIdentity("other"))) {
                    assertThat(TrustedLogContext.currentFields()).containsEntry("correlation_id", "other");
                }
                assertThat(TrustedLogContext.currentFields()).isEmpty();
                return true;
            });
            Thread.ofPlatform().start(other);
            assertThat(other.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(TrustedLogContext.currentFields()).containsEntry("correlation_id", "inner");
        } finally {
            inner.close();
            assertThat(TrustedLogContext.currentFields()).containsEntry("correlation_id", "outer");
            outer.close();
            assertThat(MDC.get("outer-key")).isEqualTo("retained");
            MDC.remove("outer-key");
        }
        assertThat(TrustedLogContext.currentFields()).isEmpty();
    }

    private static SpanContext span(String spanId) {
        return SpanContext.create("12345678901234567890123456789012", spanId,
                TraceFlags.getDefault(), TraceState.getDefault());
    }

    private static ExecutionContext identity(ActorType type, String id) {
        return new ExecutionContext(ExecutionScope.platform(), new Actor(type, id, Set.of()),
                new Initiator(type, id), "execution");
    }
}
