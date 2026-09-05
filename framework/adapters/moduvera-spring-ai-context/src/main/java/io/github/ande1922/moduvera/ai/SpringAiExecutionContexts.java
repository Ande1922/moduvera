package io.github.ande1922.moduvera.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

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
     * request channels. Additional Advisor and tool context entries supplied here are retained;
     * effective defaults and direct request configuration are validated when the request runs.
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

    /**
     * Wraps a tool callback with a stateless execution-context boundary. Every invocation reads
     * the current {@link ToolContext}; the wrapper captures no request identity and may be shared
     * when its delegate is thread-safe. The context-free callback entry point is unsupported.
     */
    public static ToolCallback toolCallback(ToolCallback delegate) {
        return new ExecutionContextToolCallback(Objects.requireNonNull(delegate, "delegate"));
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

    private static ExecutionContext requireToolContext(ToolContext toolContext) {
        if (toolContext == null) {
            throw new IllegalStateException("ToolContext is missing the reserved execution context");
        }
        Map<String, Object> context = toolContext.getContext();
        if (!context.containsKey(EXECUTION_CONTEXT_KEY)) {
            throw new IllegalStateException("ToolContext is missing the reserved execution context");
        }
        Object candidate = context.get(EXECUTION_CONTEXT_KEY);
        if (!(candidate instanceof ExecutionContext executionContext)) {
            throw new IllegalStateException("ToolContext reserved execution context has the wrong type");
        }
        return executionContext;
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

    private static Map<String, Object> withoutReservedContext(Map<String, Object> context) {
        Map<String, Object> configuration = new LinkedHashMap<>(context);
        configuration.remove(EXECUTION_CONTEXT_KEY);
        return Collections.unmodifiableMap(configuration);
    }

    /** Fixed request-scoped output containing both native context channels. */
    public static final class RequestContext {

        private final ExecutionContext executionContext;
        private final Map<String, Object> advisorContext;
        private final Map<String, Object> toolContext;
        private final Map<String, Object> advisorConfiguration;
        private final Map<String, Object> toolConfiguration;

        private RequestContext(
                ExecutionContext executionContext,
                Map<String, Object> advisorContext,
                Map<String, Object> toolContext) {
            this.executionContext = executionContext;
            this.advisorContext = advisorContext;
            this.toolContext = toolContext;
            this.advisorConfiguration = withoutReservedContext(advisorContext);
            this.toolConfiguration = withoutReservedContext(toolContext);
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

        /**
         * Adds the non-reserved configuration and a request-scoped native boundary Advisor. The
         * Advisor validates the fully assembled request before it injects the captured value.
         */
        public ChatClient.ChatClientRequestSpec applyTo(ChatClient.ChatClientRequestSpec request) {
            Objects.requireNonNull(request, "request");
            ChatClient.ChatClientRequestSpec configured = request;
            if (!advisorConfiguration.isEmpty()) {
                configured = configured.advisors(advisors -> advisors.params(advisorConfiguration));
            }
            if (!toolConfiguration.isEmpty()) {
                configured = configured.toolContext(toolConfiguration);
            }
            return configured.advisors(new NativeExecutionContextAdvisor(executionContext));
        }
    }

    private static final class NativeExecutionContextAdvisor implements CallAdvisor, StreamAdvisor {

        private final ExecutionContext captured;

        private NativeExecutionContextAdvisor(ExecutionContext captured) {
            this.captured = captured;
        }

        @Override
        public ChatClientResponse adviseCall(
                ChatClientRequest request, CallAdvisorChain advisorChain) {
            return advisorChain.nextCall(bind(request));
        }

        @Override
        public Flux<ChatClientResponse> adviseStream(
                ChatClientRequest request, StreamAdvisorChain advisorChain) {
            return advisorChain.nextStream(bind(request));
        }

        @Override
        public String getName() {
            return "Moduvera Execution Context";
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        private ChatClientRequest bind(ChatClientRequest request) {
            Map<String, Object> advisorContext = new LinkedHashMap<>(request.context());
            rejectDifferentContext(advisorContext, "Advisor request context");

            ChatOptions options = request.prompt().getOptions();
            if (!(options instanceof ToolCallingChatOptions toolOptions)) {
                throw new IllegalStateException(
                        "Spring AI request does not expose ToolCallingChatOptions for ToolContext");
            }
            rejectDifferentContext(toolOptions.getToolContext(), "ToolContext");

            advisorContext.put(EXECUTION_CONTEXT_KEY, captured);
            ToolCallingChatOptions boundOptions = toolOptions.mutate()
                .toolContext(Map.of(EXECUTION_CONTEXT_KEY, captured))
                .build();
            return request.mutate()
                .context(advisorContext)
                .prompt(request.prompt().mutate().chatOptions(boundOptions).build())
                .build();
        }

        private void rejectDifferentContext(Map<String, ?> context, String channel) {
            if (context != null
                    && context.containsKey(EXECUTION_CONTEXT_KEY)
                    && context.get(EXECUTION_CONTEXT_KEY) != captured) {
                throw new IllegalStateException(
                        channel + " contains a different reserved execution context");
            }
        }
    }

    private static final class ExecutionContextToolCallback implements ToolCallback {

        private final ToolCallback delegate;

        private ExecutionContextToolCallback(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("Execution-context tool callback requires ToolContext");
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            ExecutionContext context = requireToolContext(toolContext);
            try (ExecutionContextHolder.Scope ignored = ExecutionContextHolder.open(context)) {
                return delegate.call(toolInput, toolContext);
            }
        }
    }
}
