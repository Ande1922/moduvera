package io.github.ande1922.moduvera.reference.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.TenantId;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class GatewayRequestDiagnosticsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final SpanContext SERVER = SpanContext.create(
            "1234567890abcdef1234567890abcdef", "1234567890abcdef", TraceFlags.getDefault(), TraceState.getDefault());

    @Test
    void canonicalWaitsForBodyCompletionBeyondBeforeCommit() throws Exception {
        Sinks.Empty<Void> release = Sinks.empty();
        CountDownLatch subscribed = new CountDownLatch(1);
        var response = new MockServerHttpResponse();
        HttpHandler handler = handler(exchange -> exchange.getResponse().writeWith(Flux.concat(
                        Mono.just(exchange.getResponse().bufferFactory().wrap("prefix".getBytes(StandardCharsets.UTF_8))),
                        release.asMono().then(Mono.empty()))
                .doOnSubscribe(subscription -> subscribed.countDown())));
        try (GatewayLogProbe logs = new GatewayLogProbe()) {
            var completion = handler.handle(MockServerHttpRequest.get("/delayed").build(), response).toFuture();
            assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(response.isCommitted()).isTrue();
            assertThat(response.getHeaders().getFirst(GatewayRequestDiagnostics.HEADER)).isNotBlank();
            assertThat(completion).isNotDone();
            assertThat(logs.canonical()).isEmpty();
            release.tryEmitEmpty().orThrow();
            completion.get(5, TimeUnit.SECONDS);
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(logs.canonical()).hasSize(1));
            assertThat(logs.canonical().getFirst().path("event").path("outcome").asString()).isEqualTo("success");
            assertThat(logs.canonical().getFirst().path("http").path("response").path("status_code").asInt())
                    .isEqualTo(200);
        }
    }

    @Test
    void cancellationMasksIncidentalMdcAndRestoresTheCancellingThread() {
        HttpHandler handler = handler(exchange -> Mono.never());
        var response = new MockServerHttpResponse();
        try (GatewayLogProbe logs = new GatewayLogProbe()) {
            reactor.core.Disposable request;
            try (var ignored = Context.root().with(Span.wrap(SERVER)).makeCurrent()) {
                request = handler.handle(MockServerHttpRequest.get("/cancel").build(), response).subscribe();
            }
            Map<String, String> original = MDC.getCopyOfContextMap();
            MDC.put("tenant_id", "incidental-tenant");
            MDC.put("correlation_id", "incidental-correlation");
            MDC.put("outer", "keep-me");
            Map<String, String> cancelling = MDC.getCopyOfContextMap();
            var incidental = ExecutionContext.initiatedBy(new TenantId("same-tenant"),
                    new Actor(ActorType.USER, "incidental-user", Set.of()), "incidental-correlation");
            try (var ignored = ExecutionContextHolder.open(incidental)) {
                request.dispose();
                assertThat(MDC.getCopyOfContextMap()).isEqualTo(cancelling);
                assertThat(ExecutionContextHolder.current()).contains(incidental);
                assertThat(Span.current().getSpanContext().isValid()).isFalse();
                assertThat(logs.canonical()).hasSize(1);
                var event = logs.canonical().getFirst();
                assertThat(event.path("event").path("outcome").asString()).isEqualTo("unknown");
                assertThat(event.path("trace_id").asString()).isEqualTo(SERVER.getTraceId());
                assertThat(event.path("span_id").asString()).isEqualTo(SERVER.getSpanId());
                assertThat(event.has("tenant_id")).isFalse();
                assertThat(event.has("actor_id")).isFalse();
                assertThat(event.path("http").has("response")).isFalse();
                assertThat(event.path("correlation_id").asString()).isNotEqualTo("incidental-correlation");
                assertThat(logs.errors()).isEmpty();
            } finally {
                MDC.setContextMap(original == null ? Map.of() : original);
            }
        }
    }

    @Test
    void repeatedSubscriptionsAndRetryKeepStateInReactorContextWithoutCrossRequestIdentity() throws Exception {
        var scheduler = Schedulers.newSingle("gateway-context-test");
        HttpHandler handler = handler(exchange -> Mono.deferContextual(context -> {
            AtomicInteger attempts = new AtomicInteger();
            GatewayRequestDiagnostics state = context.get(GatewayRequestDiagnostics.class);
            return Mono.defer(() -> attempts.getAndIncrement() == 0
                            ? Mono.<String>error(new IllegalStateException("retry fixture"))
                            : Mono.just(state.correlationId()))
                    .retry(1).publishOn(scheduler)
                    .flatMap(value -> Mono.deferContextual(restored -> {
                        GatewayRequestDiagnostics restoredState = restored.get(GatewayRequestDiagnostics.class);
                        assertThat(restoredState).isSameAs(state);
                        assertThat(ExecutionContextHolder.current()).isEmpty();
                        return exchange.getResponse().writeWith(Mono.just(
                                exchange.getResponse().bufferFactory().wrap(value.getBytes(StandardCharsets.UTF_8))));
                    }));
        }));
        try (GatewayLogProbe logs = new GatewayLogProbe()) {
            var first = new MockServerHttpResponse();
            var second = new MockServerHttpResponse();
            var parent = ExecutionContext.initiatedBy(new TenantId("same-tenant"),
                    new Actor(ActorType.USER, "parent-user", Set.of()), "parent-correlation");
            java.util.concurrent.CompletableFuture<Void> one;
            java.util.concurrent.CompletableFuture<Void> two;
            try (var ignored = ExecutionContextHolder.open(parent)) {
                one = handler.handle(MockServerHttpRequest.get("/retry/one").build(), first).toFuture();
                two = handler.handle(MockServerHttpRequest.get("/retry/two").build(), second).toFuture();
                assertThat(ExecutionContextHolder.require()).isSameAs(parent);
            }
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(logs.canonical()).hasSize(2));
            assertThat(first.getBodyAsString().block(TIMEOUT)).isNotEqualTo(second.getBodyAsString().block(TIMEOUT));
            assertThat(logs.canonical()).allSatisfy(event -> {
                assertThat(event.has("tenant_id")).isFalse();
                assertThat(event.has("actor_id")).isFalse();
                assertThat(event.path("correlation_id").asString()).isNotEqualTo("parent-correlation");
                assertThat(event.path("event").path("outcome").asString()).isEqualTo("success");
            });
            assertThat(logs.errors()).isEmpty();
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void responseWriteFailureKeepsCommittedStatusAndDoesNotReportSuccess() {
        var response = new MockServerHttpResponse();
        HttpHandler handler = handler(exchange -> exchange.getResponse().writeWith(Flux.concat(
                Mono.just(exchange.getResponse().bufferFactory().wrap(new byte[] {1})),
                Mono.error(new IOException("response transport failed")))));
        try (GatewayLogProbe logs = new GatewayLogProbe()) {
            assertThatThrownBy(() -> handler.handle(MockServerHttpRequest.get("/write-failure").build(), response)
                    .block(TIMEOUT)).isInstanceOf(RuntimeException.class);
            assertThat(logs.canonical()).hasSize(1);
            var event = logs.canonical().getFirst();
            assertThat(event.path("event").path("outcome").asString()).isEqualTo("failure");
            assertThat(event.path("http").path("response").path("status_code").asInt()).isEqualTo(200);
            assertThat(event.path("log").path("level").asString()).isEqualTo("INFO");
            assertThat(logs.errors()).isEmpty();
        }
    }

    @Test
    void localUnexpectedErrorProducesOneSafeProblemAndOneFinalErrorAcrossScheduler() {
        var response = new MockServerHttpResponse();
        HttpHandler handler = handler(exchange -> Mono.delay(Duration.ZERO, Schedulers.parallel())
                .then(Mono.error(new IllegalStateException("failed https://example.invalid/path?credential=private"))));
        try (GatewayLogProbe logs = new GatewayLogProbe()) {
            handler.handle(MockServerHttpRequest.get("/failure?credential=private").build(), response).block(TIMEOUT);
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(logs.canonical()).hasSize(1));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            var body = new ObjectMapper().readTree(response.getBodyAsString().block(TIMEOUT));
            assertThat(body.path("correlationId").asString())
                    .isEqualTo(response.getHeaders().getFirst(GatewayRequestDiagnostics.HEADER));
            assertThat(body.path("code").asString()).isEqualTo("system.unexpected");
            assertThat(body.has("traceId")).isFalse();
            assertThat(logs.errors()).hasSize(1);
            assertThat(logs.errors().getFirst().path("error").path("code").asString()).isEqualTo("SYS_UNEXPECTED");
            assertThat(logs.errors().getFirst().toString()).doesNotContain("private");
            assertThat(logs.errors().getFirst().has("duration_ms")).isFalse();
            assertThat(logs.canonical().getFirst().path("event").path("outcome").asString()).isEqualTo("failure");
            assertThat(logs.canonical().getFirst().path("url").path("path").asString()).isEqualTo("/failure");
        }
    }

    private static HttpHandler handler(WebHandler application) {
        return WebHttpHandlerBuilder.webHandler(application)
                .filter(GatewayRequestDiagnostics::bind)
                .exceptionHandler(new GatewayErrorHandler(new GatewayProblemWriter(JsonMapper.builder()
                        .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build())))
                .httpHandlerDecorator(GatewayRequestDiagnostics::decorate)
                .build();
    }
}
