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
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class DiagnosticLogSnapshotTest {
    @Test void fieldsOnlyScopeDoesNotReplaceCurrentTransportContextOrAuthorization() {
        var original = SpanContext.create("12345678901234567890123456789012", "1234567890123456",
                TraceFlags.getSampled(), TraceState.getDefault());
        DiagnosticLogSnapshot captured;
        try (var ignored = Context.root().with(Span.wrap(original)).makeCurrent()) {
            captured = DiagnosticLogSnapshot.capture("finished-attempt");
        }
        Context outer = Context.current();
        MDC.put("correlation_id", "outer-mdc");
        try (var ignored = captured.openFieldsScope()) {
            assertThat(Context.current()).isSameAs(outer);
            assertThat(ExecutionContextHolder.current()).isEmpty();
            assertThat(MDC.get("correlation_id")).isEqualTo("outer-mdc");
            assertThat(TrustedLogContext.currentFields()).containsEntry("trace_id", original.getTraceId())
                    .containsEntry("correlation_id", "finished-attempt");
        } finally {
            MDC.remove("correlation_id");
        }
        assertThat(DiagnosticLogSnapshot.currentFields()).isNull();
        assertThat(Context.current()).isSameAs(outer);
    }

    @Test void identityEnrichmentRetainsServerTraceAndCorrelationWithoutRestoringAuthorization() {
        var server = SpanContext.create("12345678901234567890123456789012", "1234567890123456",
                TraceFlags.getDefault(), TraceState.getDefault());
        DiagnosticLogSnapshot snapshot;
        try (var ignored = Context.root().with(Span.wrap(server)).makeCurrent()) {
            snapshot = DiagnosticLogSnapshot.capture("request-correlation");
        }
        var identity = new ExecutionContext(ExecutionScope.platform(),
                new Actor(ActorType.USER, "user", java.util.Set.of()),
                new Initiator(ActorType.USER, "user"), "other-correlation");
        snapshot = snapshot.withIdentity(identity);
        try (var ignored = snapshot.openScope()) {
            assertThat(ExecutionContextHolder.current()).isEmpty();
            assertThat(TrustedLogContext.currentFields()).containsEntry("correlation_id", "request-correlation")
                    .containsEntry("trace_id", server.getTraceId()).containsEntry("user_id", "user");
            assertThat(TrustedLogContext.currentFields()).doesNotContainKey("tenant_id");
        }
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test void nestedAndWrongThreadCloseLeaveStateUntouchedUntilValidClose() throws Exception {
        MDC.put("unrelated", "retained");
        var outer = DiagnosticLogSnapshot.capture("outer").openScope();
        var inner = DiagnosticLogSnapshot.capture("inner").openScope();
        try {
            assertThatThrownBy(outer::close).isInstanceOf(IllegalStateException.class);
            var closeAttempt = new java.util.concurrent.FutureTask<>(() -> {
                assertThatThrownBy(inner::close).isInstanceOf(IllegalStateException.class);
                return true;
            });
            Thread.ofPlatform().start(closeAttempt);
            assertThat(closeAttempt.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(TrustedLogContext.currentFields()).containsEntry("correlation_id", "inner");
            assertThat(TrustedLogContext.currentFields()).doesNotContainKey("actor_id");
            assertThat(MDC.get("unrelated")).isEqualTo("retained");
        } finally {
            inner.close();
            assertThat(TrustedLogContext.currentFields()).containsEntry("correlation_id", "outer");
            outer.close();
            MDC.remove("unrelated");
        }
        assertThat(DiagnosticLogSnapshot.currentFields()).isNull();
    }
}
