package io.github.ande1922.moduvera.verification.ai;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** Test-source model fixture shared by Spring AI context consumer scenarios. */
final class ScriptedChatModel implements ChatModel {

    private final CountDownLatch expectedCalls;
    private final CountDownLatch releaseSignals = new CountDownLatch(1);
    private final Scheduler signalScheduler;
    private final Function<Prompt, ChatResponse> responseScript;
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final List<String> signalThreads = new CopyOnWriteArrayList<>();

    ScriptedChatModel(int expectedCalls, Scheduler signalScheduler) {
        this(
                expectedCalls,
                signalScheduler,
                prompt -> new ChatResponse(List.of(new Generation(
                        new AssistantMessage(prompt.getUserMessage().getText())))));
    }

    ScriptedChatModel(
            int expectedCalls,
            Scheduler signalScheduler,
            Function<Prompt, ChatResponse> responseScript) {
        this.expectedCalls = new CountDownLatch(expectedCalls);
        this.signalScheduler = signalScheduler;
        this.responseScript = responseScript;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        throw new UnsupportedOperationException("consumer verifies streaming only");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            prompts.add(prompt);
            expectedCalls.countDown();
            return Mono.fromCallable(() -> {
                await(releaseSignals, Duration.ofSeconds(10), "release scripted model signal");
                signalThreads.add(Thread.currentThread().getName());
                return responseScript.apply(prompt);
            }).subscribeOn(signalScheduler).flux();
        });
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    boolean awaitExpectedCalls(Duration timeout) throws InterruptedException {
        return expectedCalls.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    void releaseSignals() {
        releaseSignals.countDown();
    }

    Prompt prompt(String userText) {
        return prompts.stream()
            .filter(prompt -> userText.equals(prompt.getUserMessage().getText()))
            .findFirst()
            .orElseThrow();
    }

    List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    List<String> signalThreads() {
        return List.copyOf(signalThreads);
    }

    private static void await(CountDownLatch latch, Duration timeout, String operation) {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting to " + operation);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to " + operation, exception);
        }
    }
}
