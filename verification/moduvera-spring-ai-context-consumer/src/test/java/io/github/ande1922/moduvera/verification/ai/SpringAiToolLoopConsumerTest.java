package io.github.ande1922.moduvera.verification.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ande1922.moduvera.ai.SpringAiExecutionContexts;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class SpringAiToolLoopConsumerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String TOOL_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"request\":{\"type\":\"string\"},"
                    + "\"round\":{\"type\":\"integer\"}}}";
    private static final ExecutionContext WORKER = tenantContext("worker", "worker", "worker-correlation");

    @Test
    void sharedWrapperIsolatesConcurrentRequestsThroughTwoToolRoundsAndFinalStream()
            throws Exception {
        List<RequestCase> requests = List.of(
                new RequestCase("request-0", tenantContext("shared", "alice", "correlation-a")),
                new RequestCase("request-1", tenantContext("shared", "bob", "correlation-b")),
                new RequestCase("request-2", platformContext("operator", "correlation-platform")));
        RecordingTool delegate = new RecordingTool();
        ToolCallback sharedTool = SpringAiExecutionContexts.toolCallback(delegate);
        ConcurrentMap<String, AtomicInteger> rounds = new ConcurrentHashMap<>();

        try (ToolLoopRuntime runtime = new ToolLoopRuntime(
                requests.size(),
                requests.size() * 3,
                prompt -> scriptedRound(prompt, rounds))) {
            ChatClient client = ChatClient.builder(runtime.model())
                .defaultAdvisors(ToolCallingAdvisor.builder().build())
                .build();
            List<Flux<FinalObservation>> streams = new ArrayList<>();

            for (RequestCase request : requests) {
                Flux<ChatClientResponse> responseStream = ExecutionContextHolder.call(
                        request.context(),
                        () -> AiRequestConsumer.stream(
                                client.prompt()
                                    .system("system-without-identity")
                                    .user(request.name())
                                    .tools(sharedTool)
                                    .options(ToolCallingChatOptions.builder().temperature(0.2)),
                                Map.of("advisor-setting", request.name()),
                                Map.of("tool-setting", request.name())));
                streams.add(responseStream
                    .publishOn(runtime.workerScheduler())
                    .map(SpringAiExecutionContexts.responseMapper(response -> {
                        assertCompleteContext(request.context());
                        assertThat(response.context())
                                .containsEntry("advisor-setting", request.name())
                                .containsEntry(
                                        SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY,
                                        request.context());
                        return new FinalObservation(
                                response.chatResponse().getResult().getOutput().getText(),
                                ExecutionContextHolder.require(),
                                Thread.currentThread().getName());
                    }))
                    .map(observation -> {
                        assertThat(ExecutionContextHolder.require()).isSameAs(WORKER);
                        return observation;
                    }));
            }

            runtime.releaseModelSignals();
            List<FinalObservation> results = Flux.merge(streams)
                .collectList()
                .block(TIMEOUT);

            assertThat(results).isNotNull().hasSize(requests.size());
            for (RequestCase request : requests) {
                assertThat(results).anySatisfy(result -> {
                    assertThat(result.content()).isEqualTo("final:" + request.name());
                    assertThat(result.context()).isSameAs(request.context());
                    assertThat(result.thread()).startsWith("ai-tool-response-worker-");
                });
                assertThat(delegate.observations(request.name()))
                        .extracting(ToolObservation::context)
                        .containsExactly(request.context(), request.context());
                assertThat(delegate.observations(request.name()))
                        .extracting(ToolObservation::input)
                        .containsExactly(
                                arguments(request.name(), 1),
                                arguments(request.name(), 2));
            }

            assertThat(runtime.model().modelCalls()).isEqualTo(requests.size() * 3);
            assertThat(runtime.model().signalThreads())
                    .allMatch(thread -> thread.startsWith("scripted-tool-loop-model-"));
            assertThat(delegate.allThreads())
                    .allMatch(thread -> thread.startsWith("boundedElastic-"));
            assertThat(delegate.definition()).isSameAs(sharedTool.getToolDefinition());
            assertThat(delegate.metadata()).isSameAs(sharedTool.getToolMetadata());
            assertThat(sharedTool.getToolDefinition().name()).isEqualTo("context_lookup");
            assertThat(sharedTool.getToolDefinition().description())
                    .isEqualTo("Returns a deterministic non-identity test value");
            assertThat(sharedTool.getToolDefinition().inputSchema())
                    .isEqualTo(TOOL_SCHEMA);

            for (Prompt prompt : runtime.model().prompts()) {
                RequestCase request = requests.stream()
                    .filter(candidate -> candidate.name().equals(requestName(prompt)))
                    .findFirst()
                    .orElseThrow();
                assertThat(prompt.getContents())
                        .contains("system-without-identity", request.name())
                        .doesNotContain(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY)
                        .doesNotContain("alice", "bob", "operator")
                        .doesNotContain("correlation-a", "correlation-b", "correlation-platform");
                ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
                assertThat(options.getTemperature()).isEqualTo(0.2);
                assertThat(options.getToolCallbacks()).containsExactly(sharedTool);
                assertThat(options.getToolContext())
                        .containsEntry("tool-setting", request.name())
                        .containsEntry(
                                SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY,
                                request.context());
            }
            assertThat(delegate.allResults())
                    .allMatch(result -> !result.contains(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY))
                    .allMatch(result -> !result.contains("alice")
                            && !result.contains("bob")
                            && !result.contains("operator"))
                    .allMatch(result -> !result.contains("correlation-a")
                            && !result.contains("correlation-b")
                            && !result.contains("correlation-platform"));
            probeThreadsAreClear(delegate.allThreads());
        }
    }

    @Test
    void realToolLoopRejectsMissingAndWrongContextBeforeDelegate() {
        for (Map<String, Object> toolContext : List.of(
                Map.<String, Object>of(),
                Map.<String, Object>of(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY, "wrong"))) {
            RecordingTool delegate = new RecordingTool();
            ToolCallback sharedTool = SpringAiExecutionContexts.toolCallback(delegate);
            try (ToolLoopRuntime runtime = new ToolLoopRuntime(
                    1, 1, prompt -> toolCallResponse("request-invalid", 1))) {
                ChatClient client = ChatClient.builder(runtime.model())
                    .defaultAdvisors(ToolCallingAdvisor.builder().build())
                    .build();
                runtime.releaseModelSignals();

                Throwable failure = catchThrowable(() -> client.prompt()
                    .user("request-invalid")
                    .tools(sharedTool)
                    .options(ToolCallingChatOptions.builder().toolContext(toolContext))
                    .stream()
                    .chatClientResponse()
                    .blockLast(TIMEOUT));

                assertThat(failure).isInstanceOf(IllegalStateException.class);
                assertThat(failure.getMessage()).contains(
                        toolContext.isEmpty() ? "missing" : "wrong type");
                assertThat(delegate.callCount()).isZero();
                assertThat(runtime.model().modelCalls()).isOne();
            }
        }
    }

    @Test
    void realToolLoopPreservesFailureAndClearsActualToolThread() throws Exception {
        ToolFailure expected = new ToolFailure();
        FailingTool delegate = new FailingTool(expected);
        ToolCallback sharedTool = SpringAiExecutionContexts.toolCallback(delegate);
        ExecutionContext request = tenantContext("failure", "carol", "correlation-failure");

        try (ToolLoopRuntime runtime = new ToolLoopRuntime(
                1, 1, prompt -> toolCallResponse("request-failure", 1))) {
            ChatClient client = ChatClient.builder(runtime.model())
                .defaultAdvisors(ToolCallingAdvisor.builder().build())
                .build();
            runtime.releaseModelSignals();

            Throwable failure = catchThrowable(() -> ExecutionContextHolder.call(
                    request,
                    () -> AiRequestConsumer.stream(
                                    client.prompt()
                                        .user("request-failure")
                                        .tools(sharedTool),
                                    Map.of(),
                                    Map.of())
                            .blockLast(TIMEOUT)));

            assertThat(failure).isSameAs(expected);
            assertThat(delegate.observedContext()).isSameAs(request);
            assertThat(delegate.thread()).startsWith("boundedElastic-");
            probeThreadsAreClear(Set.of(delegate.thread()));
        }
    }

    private static ChatResponse scriptedRound(
            Prompt prompt, ConcurrentMap<String, AtomicInteger> rounds) {
        String request = requestName(prompt);
        int round = rounds.computeIfAbsent(request, ignored -> new AtomicInteger()).incrementAndGet();
        List<String> priorToolResults = prompt.getInstructions().stream()
            .filter(ToolResponseMessage.class::isInstance)
            .map(ToolResponseMessage.class::cast)
            .flatMap(message -> message.getResponses().stream())
            .map(ToolResponseMessage.ToolResponse::responseData)
            .toList();
        List<String> expectedResults = new ArrayList<>();
        for (int completedRound = 1; completedRound < round; completedRound++) {
            expectedResults.add("tool-result:" + arguments(request, completedRound));
        }
        assertThat(priorToolResults).containsExactlyElementsOf(expectedResults);
        return round <= 2
                ? toolCallResponse(request, round)
                : new ChatResponse(List.of(new Generation(new AssistantMessage("final:" + request))));
    }

    private static ChatResponse toolCallResponse(String request, int round) {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-" + request + "-" + round,
                "function",
                "context_lookup",
                arguments(request, round));
        AssistantMessage message = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(toolCall))
            .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static String requestName(Prompt prompt) {
        return prompt.getInstructions().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .map(UserMessage::getText)
            .findFirst()
            .orElseThrow();
    }

    private static String arguments(String request, int round) {
        return "{\"request\":\"" + request + "\",\"round\":" + round + "}";
    }

    private static void assertCompleteContext(ExecutionContext expected) {
        ExecutionContext actual = ExecutionContextHolder.require();
        assertThat(actual.scope()).isEqualTo(expected.scope());
        assertThat(actual.actor()).isEqualTo(expected.actor());
        assertThat(actual.initiator()).isEqualTo(expected.initiator());
        assertThat(actual.correlationId()).isEqualTo(expected.correlationId());
    }

    private static void probeThreadsAreClear(Set<String> targetThreads) throws InterruptedException {
        CountDownLatch observed = new CountDownLatch(targetThreads.size());
        CountDownLatch probesFinished = new CountDownLatch(512);
        Set<String> remaining = ConcurrentHashMap.newKeySet();
        remaining.addAll(targetThreads);
        AtomicReference<ExecutionContext> residue = new AtomicReference<>();
        for (int index = 0; index < 512; index++) {
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    if (remaining.remove(Thread.currentThread().getName())) {
                        ExecutionContextHolder.current().ifPresent(residue::set);
                        observed.countDown();
                    }
                } finally {
                    probesFinished.countDown();
                }
            });
        }
        assertThat(observed.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .as("observe every actual tool thread after its callback returned")
                .isTrue();
        assertThat(probesFinished.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .as("finish every bounded lifecycle probe")
                .isTrue();
        assertThat(residue.get()).isNull();
    }

    private static ExecutionContext tenantContext(String tenant, String subject, String correlation) {
        return context(ExecutionScope.tenant(new TenantId(tenant)), subject, correlation);
    }

    private static ExecutionContext platformContext(String subject, String correlation) {
        return context(ExecutionScope.platform(), subject, correlation);
    }

    private static ExecutionContext context(ExecutionScope scope, String subject, String correlation) {
        Actor actor = new Actor(ActorType.USER, subject, Set.of("consumer:test"));
        return new ExecutionContext(scope, actor, Initiator.from(actor), correlation);
    }

    private record RequestCase(String name, ExecutionContext context) {}

    private record ToolObservation(String input, ExecutionContext context, String thread) {}

    private record FinalObservation(String content, ExecutionContext context, String thread) {}

    private static final class RecordingTool implements ToolCallback {

        private final ToolDefinition definition = ToolDefinition.builder()
            .name("context_lookup")
            .description("Returns a deterministic non-identity test value")
            .inputSchema(TOOL_SCHEMA)
            .build();
        private final ToolMetadata metadata = ToolMetadata.builder().build();
        private final ConcurrentMap<String, List<ToolObservation>> observations = new ConcurrentHashMap<>();
        private final List<String> results = new CopyOnWriteArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return metadata;
        }

        @Override
        public String call(String input) {
            throw new AssertionError("context-aware entry required");
        }

        @Override
        public String call(String input, ToolContext toolContext) {
            ExecutionContext context = (ExecutionContext)
                    toolContext.getContext().get(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY);
            assertCompleteContext(context);
            String request = input.substring(input.indexOf("request-"), input.indexOf("\",\"round"));
            observations.computeIfAbsent(request, ignored -> new CopyOnWriteArrayList<>())
                .add(new ToolObservation(input, context, Thread.currentThread().getName()));
            calls.incrementAndGet();
            String result = "tool-result:" + input;
            results.add(result);
            return result;
        }

        private List<ToolObservation> observations(String request) {
            return List.copyOf(observations.getOrDefault(request, List.of()));
        }

        private Set<String> allThreads() {
            Set<String> threads = ConcurrentHashMap.newKeySet();
            observations.values().forEach(values -> values.forEach(value -> threads.add(value.thread())));
            return threads;
        }

        private List<String> allResults() {
            return List.copyOf(results);
        }

        private int callCount() {
            return calls.get();
        }

        private ToolDefinition definition() {
            return definition;
        }

        private ToolMetadata metadata() {
            return metadata;
        }
    }

    private static final class FailingTool implements ToolCallback {

        private final ToolFailure failure;
        private final AtomicReference<ExecutionContext> observedContext = new AtomicReference<>();
        private final AtomicReference<String> thread = new AtomicReference<>();

        private FailingTool(ToolFailure failure) {
            this.failure = failure;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("context_lookup")
                .description("Fails for lifecycle verification")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        }

        @Override
        public String call(String input) {
            throw new AssertionError("context-aware entry required");
        }

        @Override
        public String call(String input, ToolContext toolContext) {
            observedContext.set(ExecutionContextHolder.require());
            thread.set(Thread.currentThread().getName());
            throw failure;
        }

        private ExecutionContext observedContext() {
            return observedContext.get();
        }

        private String thread() {
            return thread.get();
        }
    }

    private static final class ToolFailure extends RuntimeException {}

    private static final class ToolLoopRuntime implements AutoCloseable {

        private final ExecutorService workerExecutor;
        private final Scheduler workerScheduler;
        private final Scheduler modelScheduler;
        private final ScriptedChatModel model;

        private ToolLoopRuntime(
                int responseWorkers,
                int expectedModelCalls,
                java.util.function.Function<Prompt, ChatResponse> script) {
            workerExecutor = Executors.newFixedThreadPool(responseWorkers, runnable -> Thread.ofPlatform()
                .name("ai-tool-response-worker-" + System.nanoTime())
                .unstarted(() -> ExecutionContextHolder.run(WORKER, runnable)));
            workerScheduler = Schedulers.fromExecutorService(workerExecutor);
            modelScheduler = Schedulers.newParallel("scripted-tool-loop-model", responseWorkers);
            model = new ScriptedChatModel(expectedModelCalls, modelScheduler, script);
        }

        private ScriptedChatModel model() {
            return model;
        }

        private Scheduler workerScheduler() {
            return workerScheduler;
        }

        private void releaseModelSignals() {
            model.releaseSignals();
        }

        @Override
        public void close() {
            model.releaseSignals();
            workerScheduler.dispose();
            modelScheduler.dispose();
            workerExecutor.shutdownNow();
            try {
                if (!workerExecutor.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("AI tool response worker did not terminate");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while closing AI tool runtime", exception);
            }
        }
    }
}
