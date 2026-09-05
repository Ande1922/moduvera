package io.github.ande1922.moduvera.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;

/** Explicit request and synchronous response boundaries for Spring AI execution context. */
public final class SpringAiExecutionContexts {

    /** Reserved key shared by Advisor request context, ToolContext, and ChatClientResponse. */
    public static final String EXECUTION_CONTEXT_KEY =
            "io.github.ande1922.moduvera.execution-context";

    private SpringAiExecutionContexts() {}

    /**
     * Strictly captures the current trusted context once for one AI request with no additional
     * native context entries.
     */
    public static RequestContext captureRequest() {
        return captureRequest(Map.of(), Map.of());
    }

    /**
     * Strictly captures the current trusted context once and prepares both native Spring AI
     * request channels. Callers must supply existing Advisor and tool context entries here so a
     * reserved-key collision can be rejected before the request is delegated.
     *
     * <p>The returned object is fixed to this logical request and must not be stored in shared
     * defaults or reused across requests.
     */
    public static RequestContext captureRequest(
            Map<String, ?> advisorContext, Map<String, ?> toolContext) {
        ExecutionContext captured = ExecutionContextHolder.require();
        return new RequestContext(
                captured,
                withCapturedContext(advisorContext, captured, "Advisor request context"),
                withCapturedContext(toolContext, captured, "ToolContext"));
    }

    /**
     * Creates a stateless function that reads the current response context for every invocation.
     * The function may be shared when its delegate is thread-safe. It must be applied while the
     * {@link ChatClientResponse} is still available; response content alone carries no context.
     */
    public static <T> Function<ChatClientResponse, T> responseMapper(
            Function<? super ChatClientResponse, ? extends T> delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return response -> {
            ExecutionContext context = requireResponseContext(response);
            try (ExecutionContextHolder.Scope ignored = ExecutionContextHolder.open(context)) {
                return delegate.apply(response);
            }
        };
    }

    private static ExecutionContext requireResponseContext(ChatClientResponse response) {
        Objects.requireNonNull(response, "response");
        if (!response.context().containsKey(EXECUTION_CONTEXT_KEY)) {
            throw new IllegalStateException("ChatClientResponse is missing the reserved execution context");
        }
        Object candidate = response.context().get(EXECUTION_CONTEXT_KEY);
        if (!(candidate instanceof ExecutionContext context)) {
            throw new IllegalStateException(
                    "ChatClientResponse reserved execution context has the wrong type");
        }
        return context;
    }

    private static Map<String, Object> withCapturedContext(
            Map<String, ?> existing, ExecutionContext captured, String channel) {
        Objects.requireNonNull(existing, "existing");
        Map<String, Object> combined = new LinkedHashMap<>();
        existing.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(channel + " keys must contain text");
            }
            combined.put(key, Objects.requireNonNull(value, channel + " values must not be null"));
        });
        if (combined.containsKey(EXECUTION_CONTEXT_KEY)
                && combined.get(EXECUTION_CONTEXT_KEY) != captured) {
            throw new IllegalArgumentException(
                    channel + " already contains a different reserved execution context");
        }
        combined.put(EXECUTION_CONTEXT_KEY, captured);
        return Collections.unmodifiableMap(combined);
    }

    /** Fixed request-scoped output containing both native context channels. */
    public static final class RequestContext {

        private final ExecutionContext executionContext;
        private final Map<String, Object> advisorContext;
        private final Map<String, Object> toolContext;

        private RequestContext(
                ExecutionContext executionContext,
                Map<String, Object> advisorContext,
                Map<String, Object> toolContext) {
            this.executionContext = executionContext;
            this.advisorContext = advisorContext;
            this.toolContext = toolContext;
        }

        public ExecutionContext executionContext() {
            return executionContext;
        }

        public Map<String, Object> advisorContext() {
            return advisorContext;
        }

        public Map<String, Object> toolContext() {
            return toolContext;
        }

        /** Applies both captured maps to this request while retaining its other configuration. */
        public ChatClient.ChatClientRequestSpec applyTo(ChatClient.ChatClientRequestSpec request) {
            Objects.requireNonNull(request, "request");
            return request.advisors(advisors -> advisors.params(advisorContext))
                .toolContext(toolContext);
        }
    }
}
