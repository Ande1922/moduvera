package io.github.ande1922.moduvera.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.github.ande1922.moduvera.logging.ModuveraEcsStructuredLogFormatter;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = ServletRequestDiagnosticsIT.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServletRequestDiagnosticsIT {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> OUTPUT = new CopyOnWriteArrayList<>();
    private static final List<String> ERROR_ORIGINS = new CopyOnWriteArrayList<>();
    private static final List<Throwable> ERROR_CAUSES = new CopyOnWriteArrayList<>();
    private static volatile CountDownLatch started;
    private static volatile CountDownLatch release;
    private static volatile String cancelledCorrelation;
    @LocalServerPort private int port;
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
        private final ModuveraEcsStructuredLogFormatter formatter = new ModuveraEcsStructuredLogFormatter(new MockEnvironment());
        @Override protected void append(ILoggingEvent event) {
            OUTPUT.add(formatter.format(event));
            if (event.getLevel() == ch.qos.logback.classic.Level.ERROR
                    && event.getThrowableProxy() instanceof ch.qos.logback.classic.spi.ThrowableProxy proxy) {
                Throwable root = proxy.getThrowable();
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                ERROR_CAUSES.add(root);
                ERROR_ORIGINS.add(java.util.Arrays.stream(event.getCallerData())
                        .map(StackTraceElement::getClassName)
                        .filter(name -> name.startsWith("org.apache.catalina.core."))
                        .findFirst().orElse("unknown"));
            }
        }
    };

    @BeforeEach void attachOutput() {
        OUTPUT.clear();
        ERROR_CAUSES.clear();
        ERROR_ORIGINS.clear();
        appender.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(appender);
    }

    @AfterEach void detachOutput() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
        appender.stop();
    }

    @Test void publicQueriesIgnoreCallerCorrelationAndPreserveUnwrappedResponses() throws Exception {
        var response = get("/fixture/ok?token=secret-query", "caller-secret", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("plain");
        String correlation = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(UUID.fromString(correlation).version()).isEqualTo(4);
        var event = awaitEvent(correlation);
        assertThat(event.path("event").path("outcome").asString()).isEqualTo("success");
        assertThat(event.path("url").path("path").asString()).isEqualTo("/fixture/ok");
        assertThat(event.path("http").path("response").path("status_code").asInt()).isEqualTo(200);
        assertThat(event.has("actor_id")).isFalse();
        assertThat(String.join("", OUTPUT)).doesNotContain("caller-secret", "secret-query", "url.query");
    }

    @Test void waitsAfterEarlyHintsForRealAsyncCompletionAndHandlesRedispatchOnce() throws Exception {
        started = new CountDownLatch(1);
        release = new CountDownLatch(1);
        var pending = HTTP.sendAsync(request("/fixture/wait", "ignored", null), HttpResponse.BodyHandlers.ofString());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(120);
        assertThat(canonical()).isEmpty();
        release.countDown();
        var response = pending.get(5, TimeUnit.SECONDS);
        var event = awaitEvent(response.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertThat(response.body()).isEqualTo("plain");
        assertThat(event.path("duration_ms").asDouble()).isGreaterThanOrEqualTo(100);
        assertThat(event.path("http").path("response").path("status_code").asInt()).isEqualTo(200);
        assertThat(canonical()).hasSize(1);
    }

    @Test void recordsTimeoutAndUnhandledExceptionAtFinalContainerStatus() throws Exception {
        for (String path : List.of("/fixture/timeout", "/fixture/error", "/not-found")) {
            var response = get(path, "ignored", null);
            assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
            var event = awaitEvent(response.headers().firstValue("X-Correlation-Id").orElseThrow());
            assertThat(event.path("event").path("outcome").asString()).isEqualTo("failure");
            assertThat(event.path("log").path("level").asString()).isEqualTo("INFO");
        }
        assertThat(canonical()).hasSize(3);
        assertContainerErrorProjection(1);
    }

    @Test void applicationIoFailuresRetainTheActuallyReceivedErrorStatus() throws Exception {
        for (String path : List.of("/fixture/application-io", "/fixture/wrapped-application-io")) {
            var response = get(path, "ignored", null);
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).isNotEmpty();
            var event = awaitEvent(response.headers().firstValue("X-Correlation-Id").orElseThrow());
            assertThat(event.path("event").path("outcome").asString()).isEqualTo("failure");
            assertThat(event.path("http").path("response").path("status_code").asInt()).isEqualTo(500);
            response.headers().firstValue("Content-Length").ifPresent(length ->
                    assertThat(event.path("http").path("response").path("body").path("bytes").asLong())
                            .isEqualTo(Long.parseLong(length)));
        }
        assertThat(canonical()).hasSize(2);
        assertContainerErrorProjection(2);
    }

    @Test void observesClientResetWithoutClaimingResponseStatusDelivered() throws Exception {
        started = new CountDownLatch(1);
        release = new CountDownLatch(1);
        try (var socket = new java.net.Socket("localhost", port)) {
            socket.setSoLinger(true, 0);
            socket.getOutputStream().write(("GET /fixture/cancel HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        }
        release.countDown();
        var event = awaitEvent(cancelledCorrelation);
        assertThat(event.path("event").path("outcome").asString()).isEqualTo("failure");
        assertThat(event.path("http").path("response").has("status_code")).isFalse();
        assertThat(canonical()).hasSize(1);
    }

    @Test void asyncErrorAndTimeoutRecoveryUseFinalResults() throws Exception {
        var failure = get("/fixture/async-error", "ignored", null);
        assertThat(failure.statusCode()).isEqualTo(500);
        var failed = awaitEvent(failure.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertThat(failed.path("http").path("response").path("status_code").asInt()).isEqualTo(500);
        var recovered = get("/fixture/timeout-recovered", "ignored", null);
        assertThat(recovered.statusCode()).isEqualTo(204);
        var event = awaitEvent(recovered.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertThat(event.path("event").path("outcome").asString()).isEqualTo("success");
        assertThat(event.path("http").path("response").path("status_code").asInt()).isEqualTo(204);
        assertThat(canonical()).hasSize(2);
        assertContainerErrorProjection(2);
        // The intermediate dispatch log and final wrapper log refer to the identical failed request
        // and the identical original Throwable, not merely equal exception text.
        assertThat(ERROR_CAUSES).hasSize(2);
        assertThat(ERROR_CAUSES.get(0)).isSameAs(ERROR_CAUSES.get(1));
        assertThat(ERROR_ORIGINS).containsExactly("org.apache.catalina.core.ApplicationDispatcher",
                "org.apache.catalina.core.StandardWrapperValve");
    }

    @Test void usesAgentServerContextForValidInvalidAndUnsampledParents() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("moduvera.test.agent"),
                "Requires the separate locked-Agent fixture invocation");
        String trace = "12345678901234567890123456789012";
        for (String parent : List.of("00-" + trace + "-1234567890123456-01",
                "00-" + trace + "-1234567890123456-00", "invalid-trace")) {
            var response = get("/fixture/ok", "ignored", parent);
            String responseTrace = response.headers().firstValue("X-Trace-Id").orElseThrow();
            assertThat(responseTrace).hasSize(32);
            if (parent.startsWith("00-")) {
                assertThat(responseTrace).isEqualTo(trace);
            } else {
                assertThat(responseTrace).isNotEqualTo(trace);
            }
            assertThat(response.headers().firstValue("traceparent")).isEmpty();
            var event = awaitEvent(response.headers().firstValue("X-Correlation-Id").orElseThrow());
            assertThat(event.path("trace_id").asString()).isEqualTo(responseTrace);
            assertThat(event.path("span_id").asString()).hasSize(16).isNotEqualTo("1234567890123456");
        }
    }

    private static void assertContainerErrorProjection(int expected) {
        var errors = OUTPUT.stream().map(JSON::readTree)
                .filter(event -> "ERROR".equals(event.path("log").path("level").asString())).toList();
        assertThat(errors).hasSize(expected);
        for (var error : errors) {
            assertThat(error.has("correlation_id")).isTrue();
            var completion = canonical().stream()
                    .filter(event -> event.path("correlation_id").equals(error.path("correlation_id")))
                    .findFirst().orElseThrow();
            for (String field : List.of("correlation_id", "actor_id", "tenant_id", "trace_id", "span_id")) {
                assertThat(error.path(field)).as(field).isEqualTo(completion.path(field));
            }
        }
    }

    private HttpResponse<String> get(String path, String correlation, String traceparent) throws Exception {
        return HTTP.send(request(path, correlation, traceparent), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String path, String correlation, String traceparent) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("X-Correlation-Id", correlation);
        if (traceparent != null) {
            builder.header("traceparent", traceparent);
        }
        return builder.GET().build();
    }

    private static List<JsonNode> canonical() {
        return OUTPUT.stream().map(JSON::readTree)
                .filter(event -> "http.request".equals(event.path("log").path("logger").asString())).toList();
    }

    private static JsonNode awaitEvent(String correlation) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var matches = canonical().stream().filter(event -> correlation.equals(event.path("correlation_id").asString())).toList();
            if (!matches.isEmpty()) {
                assertThat(matches).hasSize(1);
                return matches.getFirst();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("missing canonical for " + correlation);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {
        @Bean ServletRegistrationBean<HttpServlet> diagnosticFixture() {
            var registration = new ServletRegistrationBean<HttpServlet>(new HttpServlet() {
                @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, jakarta.servlet.ServletException {
                    switch (request.getRequestURI()) {
                        case "/fixture/error" -> throw new IllegalStateException("token=exception-secret");
                        case "/fixture/application-io" -> throw new IOException("application file read failed");
                        case "/fixture/wrapped-application-io" ->
                                throw new jakarta.servlet.ServletException(new IOException("application dependency read failed"));
                        case "/fixture/timeout" -> request.startAsync().setTimeout(100);
                        case "/fixture/async-error" -> request.startAsync().dispatch("/fixture/error");
                        case "/fixture/timeout-recovered" -> {
                            var async = request.startAsync();
                            async.setTimeout(100);
                            async.addListener(new jakarta.servlet.AsyncListener() {
                                @Override public void onTimeout(jakarta.servlet.AsyncEvent event) {
                                    response.setStatus(204);
                                    async.complete();
                                }
                                @Override public void onComplete(jakarta.servlet.AsyncEvent event) {}
                                @Override public void onError(jakarta.servlet.AsyncEvent event) {}
                                @Override public void onStartAsync(jakarta.servlet.AsyncEvent event) {}
                            });
                        }
                        case "/fixture/cancel" -> {
                            var async = request.startAsync();
                            cancelledCorrelation = ServletRequestDiagnostics.find(request).correlationId();
                            Thread.ofPlatform().start(() -> {
                                started.countDown();
                                try (var ignored = io.opentelemetry.context.Context.root().makeCurrent()) {
                                    if (release.await(5, TimeUnit.SECONDS)) {
                                        response.getOutputStream().write(new byte[65536]);
                                        response.flushBuffer();
                                    }
                                } catch (IOException expectedDisconnect) {
                                    // The production response wrapper observes the actual socket failure.
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    async.complete();
                                }
                            });
                        }
                        case "/fixture/wait" -> {
                            // Tomcat's Servlet 6.1 response sends 103 without completing the response.
                            response.sendError(103);
                            AsyncContext async = request.startAsync();
                            async.setTimeout(5000);
                            // Deliberately raw thread: the canonical must not depend on a live callback Span.
                            Thread.ofPlatform().start(() -> {
                                started.countDown();
                                try {
                                    if (release.await(5, TimeUnit.SECONDS)) {
                                        async.dispatch("/fixture/ok");
                                    }
                                } catch (InterruptedException failure) {
                                    Thread.currentThread().interrupt();
                                    async.complete();
                                }
                            });
                        }
                        default -> {
                            response.setContentType("text/plain");
                            response.getWriter().write("plain");
                        }
                    }
                }
            }, "/fixture/*");
            registration.setAsyncSupported(true);
            return registration;
        }
    }
}
