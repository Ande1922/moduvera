package io.github.ande1922.moduvera.verification.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class SpringAiContextConsumerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ExecutionContext WORKER = tenantContext("worker", "worker", "worker-0");

    @Test
    void consumesRealDelayedStreamsWithNativeRequestContextsAndConcurrentIsolation()
            throws Exception {
        List<ExecutionContext> requests = List.of(
                tenantContext("shared", "alice", "tenant-a-1"),
                tenantContext("shared", "bob", "tenant-a-2"),
                platformContext("operator", "platform-1"));

        try (TestRuntime runtime = new TestRuntime(requests.size())) {
            ChatClient client = ChatClient.create(runtime.model());
            CountDownLatch callbacksReady = new CountDownLatch(requests.size());
            CountDownLatch releaseCallbacks = new CountDownLatch(1);
            Function<ChatClientResponse, Observation> mapper = SpringAiExecutionContexts.responseMapper(response -> {
                String requestName = response.chatResponse().getResult().getOutput().getText();
                ExecutionContext expected = requests.get(Integer.parseInt(requestName.substring(8)));
                assertThat(ExecutionContextHolder.require()).isSameAs(expected);
                runtime.responses().put(requestName, response);
                callbacksReady.countDown();
                await(releaseCallbacks, "release concurrent response callbacks");
                return new Observation(requestName, expected, Thread.currentThread().getName());
            });

            List<Flux<Observation>> streams = new ArrayList<>();
            for (int index = 0; index < requests.size(); index++) {
                String requestName = "request-" + index;
                ExecutionContext request = requests.get(index);
                Flux<ChatClientResponse> responseStream = ExecutionContextHolder.call(
                        request,
                        () -> AiRequestConsumer.stream(
                                client.prompt()
                                    .system("fixed-system")
                                    .user(requestName)
                                    .options(ToolCallingChatOptions.builder().temperature(0.25)),
                                Map.of("advisor-setting", requestName),
                                Map.of("tool-setting", requestName)));

                Flux<Observation> stream = responseStream
                    .publishOn(runtime.workerScheduler())
                    .map(mapper)
                    .map(observation -> {
                        assertThat(ExecutionContextHolder.require()).isSameAs(WORKER);
                        return observation;
                    });
                streams.add(stream);
            }

            var resultFuture = Flux.merge(streams).collectList().toFuture();
            assertThat(runtime.awaitSubscriptions()).isTrue();
            assertThat(resultFuture).isNotDone();
            runtime.releaseModelSignals();
            boolean allCallbacksReady;
            try {
                allCallbacksReady = callbacksReady.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                releaseCallbacks.countDown();
            }
            assertThat(allCallbacksReady).isTrue();

            List<Observation> observations = resultFuture.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(observations).hasSize(requests.size());
            assertThat(observations).extracting(Observation::request)
                    .containsExactlyInAnyOrder("request-0", "request-1", "request-2");
            assertThat(observations).extracting(Observation::context).containsExactlyInAnyOrderElementsOf(requests);
            assertThat(observations).extracting(Observation::callbackThread)
                    .allMatch(name -> name.startsWith("ai-context-worker-"));
            assertThat(runtime.model().signalThreads())
                    .allMatch(name -> name.startsWith("scripted-chat-model-"));

            for (int index = 0; index < requests.size(); index++) {
                String requestName = "request-" + index;
                Prompt prompt = runtime.model().prompt(requestName);
                assertThat(prompt.getContents()).isEqualTo("fixed-system" + requestName);
                assertThat(prompt.getContents())
                        .doesNotContain(requests.get(index).actor().subjectId())
                        .doesNotContain(requests.get(index).correlationId())
                        .doesNotContain(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY);
                ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
                assertThat(options.getTemperature()).isEqualTo(0.25);
                assertThat(options.getToolContext())
                        .containsEntry("tool-setting", requestName)
                        .containsEntry(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY, requests.get(index));
                assertThat(options.getToolCallbacks()).isNullOrEmpty();
                ChatClientResponse response = runtime.responses().get(requestName);
                assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo(requestName);
                assertThat(response.context())
                        .containsEntry("advisor-setting", requestName)
                        .containsEntry(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY, requests.get(index));
            }

            ChatClientResponse unbound = client.prompt()
                .user("unbound")
                .stream()
                .chatClientResponse()
                .blockLast(TIMEOUT);
            assertThat(unbound).isNotNull();
            assertThat(unbound.context()).doesNotContainKey(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY);
            ToolCallingChatOptions unboundOptions =
                    (ToolCallingChatOptions) runtime.model().prompt("unbound").getOptions();
            assertThat(unboundOptions.getToolContext()).isNullOrEmpty();
        }
    }

    @Test
    void restoresWorkerContextWhenRealStreamResponseHandlingFails() {
        ExecutionContext request = tenantContext("failure", "carol", "failure-1");
        try (TestRuntime runtime = new TestRuntime(1)) {
            runtime.releaseModelSignals();
            ChatClient client = ChatClient.create(runtime.model());
            Flux<ChatClientResponse> responseStream = ExecutionContextHolder.call(
                    request,
                    () -> AiRequestConsumer.stream(client.prompt().user("request-0"), Map.of(), Map.of()));
            AtomicReference<Throwable> observedFailure = new AtomicReference<>();

            ExecutionContext restored = responseStream
                .publishOn(runtime.workerScheduler())
                .map(SpringAiExecutionContexts.<ExecutionContext>responseMapper(response -> {
                    assertThat(ExecutionContextHolder.require()).isSameAs(request);
                    throw new ResponseFailure();
                }))
                .onErrorResume(failure -> {
                    observedFailure.set(failure);
                    return Mono.just(ExecutionContextHolder.require());
                })
                .blockLast(TIMEOUT);

            assertThat(observedFailure.get()).isInstanceOf(ResponseFailure.class);
            assertThat(restored).isSameAs(WORKER);
        }
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

    private static void await(CountDownLatch latch, String operation) {
        try {
            if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting to " + operation);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to " + operation, exception);
        }
    }

    private record Observation(String request, ExecutionContext context, String callbackThread) {}

    private static final class ResponseFailure extends RuntimeException {}

    private static final class TestRuntime implements AutoCloseable {

        private final ExecutorService workerExecutor;
        private final Scheduler workerScheduler;
        private final Scheduler modelScheduler;
        private final ScriptedChatModel model;
        private final Map<String, ChatClientResponse> responses = new ConcurrentHashMap<>();

        private TestRuntime(int expectedSubscriptions) {
            this.workerExecutor = Executors.newFixedThreadPool(expectedSubscriptions, runnable -> Thread.ofPlatform()
                .name("ai-context-worker-" + System.nanoTime())
                .unstarted(() -> ExecutionContextHolder.run(WORKER, runnable)));
            this.workerScheduler = Schedulers.fromExecutorService(workerExecutor);
            this.modelScheduler = Schedulers.newSingle("scripted-chat-model");
            this.model = new ScriptedChatModel(expectedSubscriptions, modelScheduler);
        }

        private ScriptedChatModel model() {
            return model;
        }

        private Scheduler workerScheduler() {
            return workerScheduler;
        }

        private boolean awaitSubscriptions() throws InterruptedException {
            return model.awaitExpectedCalls(TIMEOUT);
        }

        private void releaseModelSignals() {
            model.releaseSignals();
        }

        private Map<String, ChatClientResponse> responses() {
            return responses;
        }

        @Override
        public void close() {
            model.releaseSignals();
            workerScheduler.dispose();
            modelScheduler.dispose();
            workerExecutor.shutdownNow();
            try {
                if (!workerExecutor.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("AI context worker executor did not terminate");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while closing AI test runtime", exception);
            }
        }
    }

}
