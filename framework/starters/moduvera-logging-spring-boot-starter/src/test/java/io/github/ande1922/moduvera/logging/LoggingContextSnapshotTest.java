package io.github.ande1922.moduvera.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LoggingContextSnapshotTest {

    private static final SpanContext SAMPLED = spanContext(
            "11111111111111111111111111111111", "2222222222222222", true);
    private static final SpanContext UNSAMPLED = spanContext(
            "33333333333333333333333333333333", "4444444444444444", false);
    private static final ContextKey<String> CUSTOM_CONTEXT = ContextKey.named("fixture.custom");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void capturesCompleteTelemetryAndTrustedUserContextWithoutOpeningAnotherSpan() {
        ExecutionContext request = tenantUser("tenant-request", "user-request", "corr-request");
        LoggingContextSnapshot snapshot;
        try (var ignoredExecution = ExecutionContextHolder.open(request);
                var ignoredTelemetry = Context.root()
                        .with(Span.wrap(UNSAMPLED))
                        .with(CUSTOM_CONTEXT, "custom-value")
                        .makeCurrent()) {
            snapshot = LoggingContextSnapshot.capture();
        }

        MDC.put("outer_key", "outer-value");
        MDC.put("tenant_id", "stale-tenant");
        try (var ignoredExecution = ExecutionContextHolder.open(
                        tenantUser("tenant-worker", "user-worker", "corr-worker"));
                var ignoredTelemetry = Context.root().with(Span.wrap(SAMPLED)).makeCurrent();
                var ignoredSnapshot = snapshot.openScope()) {
            assertThat(ExecutionContextHolder.require()).isEqualTo(request);
            assertThat(Span.current().getSpanContext()).isEqualTo(UNSAMPLED);
            assertThat(Context.current().get(CUSTOM_CONTEXT)).isEqualTo("custom-value");
            assertThat(MDC.getCopyOfContextMap())
                    .containsEntry("outer_key", "outer-value")
                    .containsEntry("trace_id", UNSAMPLED.getTraceId())
                    .containsEntry("span_id", UNSAMPLED.getSpanId())
                    .containsEntry("correlation_id", "corr-request")
                    .containsEntry("tenant_id", "tenant-request")
                    .containsEntry("actor_type", "USER")
                    .containsEntry("actor_id", "user-request")
                    .containsEntry("user_id", "user-request")
                    .containsEntry("initiator_type", "USER")
                    .containsEntry("initiator_id", "user-request")
                    .doesNotContainKeys("permissions");
        }

        assertThat(MDC.getCopyOfContextMap())
                .containsEntry("outer_key", "outer-value")
                .containsEntry("tenant_id", "stale-tenant")
                .doesNotContainKeys("trace_id", "span_id", "actor_id", "user_id");
    }

    @Test
    void absentSnapshotMasksResidualBusinessTelemetryAndOwnedMdcOnly() {
        MDC.put("outer_key", "outer-value");
        MDC.put("trace_id", "stale-trace");
        MDC.put("actor_id", "stale-actor");
        try (var ignoredExecution = ExecutionContextHolder.open(
                        tenantUser("tenant-worker", "user-worker", "corr-worker"));
                var ignoredTelemetry = Context.root().with(Span.wrap(SAMPLED)).makeCurrent()) {
            try (var ignoredSnapshot = LoggingContextSnapshot.absent().openScope()) {
                assertThat(ExecutionContextHolder.current()).isEmpty();
                assertThat(Span.current().getSpanContext().isValid()).isFalse();
                assertThat(MDC.getCopyOfContextMap())
                        .containsEntry("outer_key", "outer-value")
                        .doesNotContainKeys(
                                "trace_id",
                                "span_id",
                                "correlation_id",
                                "tenant_id",
                                "actor_id",
                                "user_id");
            }

            assertThat(ExecutionContextHolder.require().correlationId()).isEqualTo("corr-worker");
            assertThat(Span.current().getSpanContext()).isEqualTo(SAMPLED);
            assertThat(MDC.getCopyOfContextMap())
                    .containsEntry("outer_key", "outer-value")
                    .containsEntry("trace_id", "stale-trace")
                    .containsEntry("actor_id", "stale-actor");
        }
    }

    @Test
    void captureAllowingAbsentPreservesCompleteTelemetryAndMasksBusinessResidue() {
        LoggingContextSnapshot snapshot;
        try (var ignoredTelemetry = Context.root()
                .with(Span.wrap(UNSAMPLED))
                .with(CUSTOM_CONTEXT, "captured-without-business")
                .makeCurrent()) {
            snapshot = LoggingContextSnapshot.captureAllowingAbsent();
        }

        MDC.put("outer_key", "outer-value");
        MDC.put("tenant_id", "stale-tenant");
        MDC.put("trace_id", "stale-trace");
        ExecutionContext outer = tenantUser("tenant-outer", "user-outer", "corr-outer");
        try (var ignoredExecution = ExecutionContextHolder.open(outer);
                var ignoredTelemetry = Context.root().with(Span.wrap(SAMPLED)).makeCurrent()) {
            try (var ignoredSnapshot = snapshot.openScope()) {
                assertThat(ExecutionContextHolder.current()).isEmpty();
                assertThat(Span.current().getSpanContext()).isEqualTo(UNSAMPLED);
                assertThat(Context.current().get(CUSTOM_CONTEXT))
                        .isEqualTo("captured-without-business");
                assertThat(MDC.getCopyOfContextMap())
                        .containsEntry("outer_key", "outer-value")
                        .containsEntry("trace_id", UNSAMPLED.getTraceId())
                        .containsEntry("span_id", UNSAMPLED.getSpanId())
                        .doesNotContainKeys(
                                "correlation_id",
                                "tenant_id",
                                "actor_type",
                                "actor_id",
                                "initiator_type",
                                "initiator_id",
                                "user_id");
            }

            assertThat(ExecutionContextHolder.require()).isEqualTo(outer);
            assertThat(Span.current().getSpanContext()).isEqualTo(SAMPLED);
            assertThat(Context.current().get(CUSTOM_CONTEXT)).isNull();
            assertThat(MDC.getCopyOfContextMap())
                    .containsEntry("outer_key", "outer-value")
                    .containsEntry("tenant_id", "stale-tenant")
                    .containsEntry("trace_id", "stale-trace");
        }
    }

    @Test
    void nestedExceptionalExitRestoresEveryPriorLayer() {
        ExecutionContext outer = tenantUser("tenant-outer", "user-outer", "corr-outer");
        ExecutionContext inner = tenantUser("tenant-inner", "user-inner", "corr-inner");
        LoggingContextSnapshot innerSnapshot;
        try (var ignoredExecution = ExecutionContextHolder.open(inner);
                var ignoredTelemetry = Context.root().with(Span.wrap(UNSAMPLED)).makeCurrent()) {
            innerSnapshot = LoggingContextSnapshot.capture();
        }

        try (var ignoredExecution = ExecutionContextHolder.open(outer);
                var ignoredTelemetry = Context.root().with(Span.wrap(SAMPLED)).makeCurrent()) {
            MDC.put("tenant_id", "outer-mdc");
            assertThatThrownBy(() -> {
                        try (var ignored = innerSnapshot.openScope()) {
                            throw new IllegalStateException("fixture failure");
                        }
                    })
                    .isInstanceOf(IllegalStateException.class);

            assertThat(ExecutionContextHolder.require()).isEqualTo(outer);
            assertThat(Span.current().getSpanContext()).isEqualTo(SAMPLED);
            assertThat(MDC.get("tenant_id")).isEqualTo("outer-mdc");
        }
    }

    @Test
    void capturedStateCanCrossARealVirtualThreadAndLeavesItClean() throws Exception {
        LoggingContextSnapshot snapshot;
        try (var ignoredExecution = ExecutionContextHolder.open(
                        tenantUser("tenant-vt", "user-vt", "corr-vt"));
                var ignoredTelemetry = Context.root().with(Span.wrap(UNSAMPLED)).makeCurrent()) {
            snapshot = LoggingContextSnapshot.capture();
        }
        AtomicReference<Observation> inside = new AtomicReference<>();
        AtomicReference<Observation> after = new AtomicReference<>();

        Thread thread = Thread.startVirtualThread(() -> {
            try (var ignored = snapshot.openScope()) {
                inside.set(observe());
            }
            after.set(observe());
        });
        thread.join();

        assertThat(inside.get().correlationId()).isEqualTo("corr-vt");
        assertThat(inside.get().spanContext()).isEqualTo(UNSAMPLED);
        assertThat(inside.get().mdcTraceId()).isEqualTo(UNSAMPLED.getTraceId());
        assertThat(after.get().correlationId()).isNull();
        assertThat(after.get().spanContext().isValid()).isFalse();
        assertThat(after.get().mdcTraceId()).isNull();
    }

    @Test
    void platformSystemContextDoesNotInventTenantOrUserIdentity() {
        ExecutionContext platform = new ExecutionContext(
                ExecutionScope.platform(),
                new Actor(ActorType.SYSTEM, "system-worker", Set.of("internal:admin")),
                new Initiator(ActorType.SERVICE, "scheduler"),
                "corr-platform");
        LoggingContextSnapshot snapshot;
        try (var ignored = ExecutionContextHolder.open(platform)) {
            snapshot = LoggingContextSnapshot.capture();
        }

        try (var ignored = snapshot.openScope()) {
            assertThat(MDC.getCopyOfContextMap())
                    .containsEntry("actor_type", "SYSTEM")
                    .containsEntry("actor_id", "system-worker")
                    .containsEntry("initiator_type", "SERVICE")
                    .containsEntry("initiator_id", "scheduler")
                    .doesNotContainKeys("tenant_id", "user_id", "permissions");
        }
    }

    @Test
    void outOfOrderClosePreservesAllLayersAndCanBeRetried() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                ExecutionContext request = tenantUser("tenant-request", "user-request", "corr-request");
                try (var ignoredExecution = ExecutionContextHolder.open(request);
                        var ignoredTelemetry = Context.root().with(Span.wrap(SAMPLED)).makeCurrent()) {
                    MDC.put("tenant_id", "prior-mdc");
                    var outer = LoggingContextSnapshot.capture().openScope();
                    var inner = LoggingContextSnapshot.absent().openScope();
                    Observation before = observe();
                    var mdcBefore = MDC.getCopyOfContextMap();
                    assertThatThrownBy(outer::close).isInstanceOf(IllegalStateException.class);
                    assertThat(observe()).isEqualTo(before);
                    assertThat(MDC.getCopyOfContextMap()).isEqualTo(mdcBefore);
                    inner.close();
                    assertThat(ExecutionContextHolder.require()).isEqualTo(request);
                    assertThat(Span.current().getSpanContext()).isEqualTo(SAMPLED);
                    outer.close();
                    outer.close();
                    assertThat(ExecutionContextHolder.require()).isEqualTo(request);
                    assertThat(Span.current().getSpanContext()).isEqualTo(SAMPLED);
                    assertThat(MDC.get("tenant_id")).isEqualTo("prior-mdc");
                }
                assertThat(ExecutionContextHolder.current()).isEmpty();
            }).get();
        }
    }

    private static Observation observe() {
        return new Observation(
                ExecutionContextHolder.current().map(ExecutionContext::correlationId).orElse(null),
                Span.current().getSpanContext(),
                MDC.get("trace_id"));
    }

    private static ExecutionContext tenantUser(String tenant, String user, String correlation) {
        Actor actor = new Actor(ActorType.USER, user, Set.of("orders:write"));
        return new ExecutionContext(
                new TenantId(tenant), actor, Initiator.from(actor), correlation);
    }

    private static SpanContext spanContext(String traceId, String spanId, boolean sampled) {
        return SpanContext.create(
                traceId,
                spanId,
                sampled ? TraceFlags.getSampled() : TraceFlags.getDefault(),
                TraceState.getDefault());
    }

    private record Observation(
            String correlationId, SpanContext spanContext, String mdcTraceId) {}
}
