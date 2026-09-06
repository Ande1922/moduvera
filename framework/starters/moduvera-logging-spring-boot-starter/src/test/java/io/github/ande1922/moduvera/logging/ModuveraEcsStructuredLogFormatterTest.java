package io.github.ande1922.moduvera.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
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
import java.net.http.HttpTimeoutException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ModuveraEcsStructuredLogFormatterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SpanContext UNSAMPLED = SpanContext.create(
            "11111111111111111111111111111111",
            "2222222222222222",
            TraceFlags.getDefault(),
            TraceState.getDefault());

    @Test
    void writesStableEcsTypesAndUsesOnlyCurrentTrustedIdentityAndTrace() throws Exception {
        LoggingEvent event = event(Level.INFO, "订单已创建, orderId={}", null, "order-42");
        event.setMDCPropertyMap(Map.of(
                "trace_id", "forged-trace",
                "tenant_id", "forged-tenant",
                "actor_id", "forged-actor",
                "outer_key", "outer-value",
                "empty_value", ""));
        event.setKeyValuePairs(List.of(
                new KeyValuePair("trace_id", "forged-key-value-trace"),
                new KeyValuePair("tenant_id", "forged-key-value-tenant"),
                new KeyValuePair("event.action", "order_created"),
                new KeyValuePair("order_id", "order-42"),
                new KeyValuePair("duration_ms", 12.5d),
                new KeyValuePair("retry.attempt", 2),
                new KeyValuePair("unknown", null)));
        Actor actor = new Actor(ActorType.USER, "user-7", Set.of("orders:write"));
        ExecutionContext executionContext = new ExecutionContext(
                new TenantId("tenant-7"), actor, Initiator.from(actor), "corr-7");

        JsonNode json;
        try (var ignoredExecution = ExecutionContextHolder.open(executionContext);
                var ignoredTelemetry = Context.root().with(Span.wrap(UNSAMPLED)).makeCurrent()) {
            json = JSON.readTree(formatter().format(event));
        }

        assertThat(json.path("@timestamp").stringValue()).isEqualTo("2026-09-06T10:11:12.123456Z");
        assertThat(json.path("log").path("level").stringValue()).isEqualTo("INFO");
        assertThat(json.path("log").path("logger").stringValue()).isEqualTo("fixture.logger");
        assertThat(json.path("process").path("pid").isIntegralNumber()).isTrue();
        assertThat(json.path("process").path("thread").path("name").stringValue())
                .isEqualTo("fixture-thread");
        assertThat(json.path("service").path("name").stringValue()).isEqualTo("logging-fixture");
        assertThat(json.path("service").path("version").stringValue()).isEqualTo("1.2.3");
        assertThat(json.path("service").path("environment").stringValue()).isEqualTo("test");
        assertThat(json.path("ecs").path("version").stringValue()).isEqualTo("8.11");
        assertThat(json.path("message").stringValue()).isEqualTo("订单已创建, orderId=order-42");
        assertThat(json.path("event").path("action").stringValue()).isEqualTo("order_created");
        assertThat(json.path("duration_ms").isFloatingPointNumber()).isTrue();
        assertThat(json.path("retry").path("attempt").isIntegralNumber()).isTrue();
        assertThat(json.path("trace_id").stringValue()).isEqualTo(UNSAMPLED.getTraceId());
        assertThat(json.path("span_id").stringValue()).isEqualTo(UNSAMPLED.getSpanId());
        assertThat(json.path("correlation_id").stringValue()).isEqualTo("corr-7");
        assertThat(json.path("tenant_id").stringValue()).isEqualTo("tenant-7");
        assertThat(json.path("actor_type").stringValue()).isEqualTo("USER");
        assertThat(json.path("actor_id").stringValue()).isEqualTo("user-7");
        assertThat(json.path("user_id").stringValue()).isEqualTo("user-7");
        assertThat(json.path("initiator_id").stringValue()).isEqualTo("user-7");
        assertThat(json.path("outer_key").stringValue()).isEqualTo("outer-value");
        assertThat(json.has("empty_value")).isFalse();
        assertThat(json.has("unknown")).isFalse();
        assertThat(json.toString()).doesNotContain("forged-");
    }

    @Test
    void mergesErrorCodeWithSafeNestedCauseAndDropsSensitiveFields() throws Exception {
        String credential = "credential-" + java.util.UUID.randomUUID();
        String query = "query-" + java.util.UUID.randomUUID();
        String sql = "sql-" + java.util.UUID.randomUUID();
        HttpTimeoutException transport =
                new HttpTimeoutException("https://example.test/orders?secret=" + query);
        SQLException failure = new SQLException("SELECT * FROM secrets WHERE value='" + sql + "'", transport);
        LoggingEvent event = event(Level.ERROR, "最终失败, credential={}", failure, credential);
        event.setMDCPropertyMap(Map.of("authorization", credential, "tenant_id", "forged"));
        event.setKeyValuePairs(List.of(
                new KeyValuePair("error.code", "DEP_DATABASE_UNAVAILABLE"),
                new KeyValuePair("db.query.text", sql),
                new KeyValuePair("http.request.body.content", credential),
                new KeyValuePair("url.query", query),
                new KeyValuePair("order_id", "order-safe")));

        String line = formatter().format(event);
        JsonNode json = JSON.readTree(line);

        assertThat(line).doesNotContain(credential, query, sql, "SELECT *", "https://example.test/orders?");
        assertThat(json.path("message").stringValue())
                .isEqualTo("最终失败, credential=[REDACTED]");
        assertThat(json.path("order_id").stringValue()).isEqualTo("order-safe");
        assertThat(json.path("error").path("code").stringValue())
                .isEqualTo("DEP_DATABASE_UNAVAILABLE");
        assertThat(json.path("error").path("type").stringValue())
                .isEqualTo(SQLException.class.getName());
        assertThat(json.path("error").path("message").stringValue())
                .isEqualTo("Failure of type " + SQLException.class.getName());
        assertThat(json.path("error").path("stack_trace").stringValue())
                .contains(SQLException.class.getName(), "Caused by: " + HttpTimeoutException.class.getName())
                .doesNotContain(credential, query, sql);
        assertThat(json.path("error").size()).isEqualTo(4);
    }

    @Test
    void warnRetainsSafeFailureTypeWithoutMessageOrStack() throws Exception {
        LoggingEvent event = event(
                Level.WARN,
                "确定重试",
                new IllegalStateException("credential=must-not-escape"));
        event.setMDCPropertyMap(Map.of());
        event.setKeyValuePairs(List.of(new KeyValuePair("retry.attempt", 1)));

        JsonNode error = JSON.readTree(formatter().format(event)).path("error");

        assertThat(error.path("type").stringValue()).isEqualTo(IllegalStateException.class.getName());
        assertThat(error.has("message")).isFalse();
        assertThat(error.has("stack_trace")).isFalse();
    }

    @Test
    void finalErrorWithoutValidCodeFailsSafeToStableDefault() throws Exception {
        LoggingEvent event = event(Level.ERROR, "最终失败", new IllegalArgumentException("unsafe"));
        event.setMDCPropertyMap(Map.of());
        event.setKeyValuePairs(List.of(new KeyValuePair("error.code", "invalid-code")));

        JsonNode error = JSON.readTree(formatter().format(event)).path("error");

        assertThat(error.path("code").stringValue()).isEqualTo("SYS_UNEXPECTED");
    }

    @Test
    void redactsSensitiveAssignmentsAndResolvesStructuredNameConflictsDeterministically()
            throws Exception {
        String credential = "credential-" + java.util.UUID.randomUUID();
        String bearer = "bearer-" + java.util.UUID.randomUUID();
        String query = "query-" + java.util.UUID.randomUUID();
        LoggingEvent event = event(
                Level.INFO,
                "认证失败, json={\"password\":\"" + credential
                        + "\"}, Authorization: Bearer " + bearer
                        + ", url=https://example.test/orders?arbitrary=" + query,
                null);
        event.setKeyValuePairs(List.of(
                new KeyValuePair("event.action", "authentication_rejected"),
                new KeyValuePair("attempt", 2),
                new KeyValuePair("custom", "scalar-first"),
                new KeyValuePair("custom.detail", "must-not-replace-parent"),
                new KeyValuePair("authorization", credential)));
        event.setMDCPropertyMap(Map.of(
                "event.action", "forged-mdc-action",
                "attempt", "forged-string-type",
                "payload", credential));

        String line = formatter().format(event);
        JsonNode json = JSON.readTree(line);

        assertThat(line)
                .doesNotContain(
                        credential,
                        bearer,
                        query,
                        "forged-mdc-action",
                        "forged-string-type");
        assertThat(json.path("message").stringValue())
                .isEqualTo(
                        "认证失败, json={password=[REDACTED]}, Authorization=[REDACTED], url=https://example.test/orders");
        assertThat(json.path("event").path("action").stringValue())
                .isEqualTo("authentication_rejected");
        assertThat(json.path("attempt").isIntegralNumber()).isTrue();
        assertThat(json.path("attempt").intValue()).isEqualTo(2);
        assertThat(json.path("custom").stringValue()).isEqualTo("scalar-first");
    }

    @Test
    void normalizesGovernedFieldTypesFromMdcAndKeyValuesAndOmitsInvalidValues()
            throws Exception {
        LoggingEvent mdcEvent = event(Level.INFO, "MDC fields", null);
        mdcEvent.setMDCPropertyMap(Map.of(
                "duration_ms", "7.25",
                "retry.attempt", "2",
                "retry.max_attempts", "3",
                "http.request.body.bytes", "64",
                "event.outcome", "success",
                "disposition", "retry"));
        JsonNode mdc = JSON.readTree(formatter().format(mdcEvent));

        assertThat(mdc.path("duration_ms").isFloatingPointNumber()).isTrue();
        assertThat(mdc.path("retry").path("attempt").isIntegralNumber()).isTrue();
        assertThat(mdc.path("retry").path("max_attempts").longValue()).isEqualTo(3);
        assertThat(mdc.path("http").path("request").path("body").path("bytes").longValue())
                .isEqualTo(64);
        assertThat(mdc.path("event").path("outcome").stringValue()).isEqualTo("success");
        assertThat(mdc.path("disposition").stringValue()).isEqualTo("retry");

        LoggingEvent keyValueEvent = event(Level.INFO, "key-value fields", null);
        keyValueEvent.setMDCPropertyMap(Map.of());
        keyValueEvent.setKeyValuePairs(List.of(
                new KeyValuePair("duration_ms", 8),
                new KeyValuePair("retry.attempt", 2),
                new KeyValuePair("retry.max_attempts", 3),
                new KeyValuePair("messaging.message.body.size", 128)));
        JsonNode keyValue = JSON.readTree(formatter().format(keyValueEvent));

        assertThat(keyValue.path("duration_ms").isFloatingPointNumber()).isTrue();
        assertThat(keyValue.path("retry").path("attempt").longValue()).isEqualTo(2);
        assertThat(keyValue.path("retry").path("max_attempts").longValue()).isEqualTo(3);
        assertThat(keyValue.path("messaging").path("message").path("body").path("size").longValue())
                .isEqualTo(128);

        LoggingEvent invalid = event(Level.INFO, "invalid fields", null);
        invalid.setMDCPropertyMap(Map.of(
                "duration_ms", "NaN",
                "retry.attempt", "2.5",
                "retry.max_attempts", "1",
                "retry.attempt.detail", "forged-child",
                "http.response.body.bytes", "-1",
                "event.outcome", "completed",
                "disposition", "discard"));
        invalid.setKeyValuePairs(List.of(
                new KeyValuePair("event.action", 42),
                new KeyValuePair("url.full", true),
                new KeyValuePair("http.url", 7)));
        JsonNode omitted = JSON.readTree(formatter().format(invalid));

        assertThat(omitted.has("duration_ms")).isFalse();
        assertThat(omitted.has("retry")).isFalse();
        assertThat(omitted.has("http")).isFalse();
        assertThat(omitted.has("event")).isFalse();
        assertThat(omitted.has("url")).isFalse();
        assertThat(omitted.has("disposition")).isFalse();
        assertThat(omitted.toString()).doesNotContain("forged-child");
    }

    @Test
    void rejectsEveryTrustedIdentityNamespaceWhenNoTrustedTenantOrUserExists()
            throws Exception {
        LoggingEvent event = event(Level.INFO, "platform work", null);
        event.setMDCPropertyMap(Map.of(
                "tenant_id.claim", "forged-tenant",
                "user_id.profile", "forged-user",
                "trace_id.value", "forged-trace"));
        event.setKeyValuePairs(List.of(
                new KeyValuePair("correlation_id.value", "forged-correlation"),
                new KeyValuePair("actor_id.alias", "forged-actor"),
                new KeyValuePair("business.reference", "business-safe")));
        ExecutionContext platform = new ExecutionContext(
                ExecutionScope.platform(),
                new Actor(ActorType.SERVICE, "fixture-service", Set.of("fixture:read")),
                new Initiator(ActorType.SERVICE, "fixture-service"),
                "corr-platform");

        JsonNode json;
        try (var ignored = ExecutionContextHolder.open(platform)) {
            json = JSON.readTree(formatter().format(event));
        }

        assertThat(json.has("tenant_id")).isFalse();
        assertThat(json.has("user_id")).isFalse();
        assertThat(json.has("trace_id")).isFalse();
        assertThat(json.path("correlation_id").stringValue()).isEqualTo("corr-platform");
        assertThat(json.path("actor_id").stringValue()).isEqualTo("fixture-service");
        assertThat(json.path("business").path("reference").stringValue())
                .isEqualTo("business-safe");
        assertThat(json.toString()).doesNotContain("forged-");
    }

    private static ModuveraEcsStructuredLogFormatter formatter() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .addFirst(new MapPropertySource(
                        "fixture",
                        Map.of(
                                "spring.application.name",
                                "logging-fixture",
                                "logging.structured.ecs.service.version",
                                "1.2.3",
                                "logging.structured.ecs.service.environment",
                                "test")));
        return new ModuveraEcsStructuredLogFormatter(environment);
    }

    private static LoggingEvent event(
            Level level, String message, Throwable failure, Object... arguments) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        event.setLoggerName("fixture.logger");
        event.setThreadName("fixture-thread");
        event.setMessage(message);
        event.setArgumentArray(arguments);
        event.setInstant(Instant.parse("2026-09-06T10:11:12.123456Z"));
        if (failure != null) {
            event.setThrowableProxy(new ThrowableProxy(failure));
        }
        return event;
    }
}
