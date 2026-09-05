package io.github.ande1922.moduvera.example.notes;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ContextExecutors;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionContextSnapshot;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JdkExecutorContextConsumerTest {

    private static final ExecutionContext REQUEST = new ExecutionContext(
            new TenantId("notes-tenant"),
            new Actor(ActorType.USER, "notes-author"),
            new Initiator(ActorType.USER, "notes-author"),
            "notes-request");
    private static final ExecutionContext SDK_WORKER = new ExecutionContext(
            new TenantId("sdk-tenant"),
            new Actor(ActorType.SYSTEM, "sdk-worker"),
            new Initiator(ActorType.SYSTEM, "sdk"),
            "sdk-completion");

    @Test
    void submissionCaptureAndCallbackRegistrationCaptureAreSeparateBoundaries() throws Exception {
        try (ExecutorService rawTasks = Executors.newSingleThreadExecutor();
                ExecutorService tasks = ContextExecutors.propagating(rawTasks);
                ExecutorService sdk = Executors.newSingleThreadExecutor()) {
            CompletableFuture<String> unboundSource = new CompletableFuture<>();
            CompletableFuture<String> boundSource = new CompletableFuture<>();

            CompletableFuture<Optional<ExecutionContext>> unboundCallback =
                    ExecutionContextHolder.call(REQUEST, () -> tasks.submit(() -> {
                                assertThat(ExecutionContextHolder.require()).isSameAs(REQUEST);
                                return unboundSource.thenApply(
                                        ignored -> ExecutionContextHolder.current());
                            }))
                            .get(2, TimeUnit.SECONDS);
            CompletableFuture<ExecutionContext> boundCallback =
                    ExecutionContextHolder.call(REQUEST, () -> tasks.submit(() -> {
                                assertThat(ExecutionContextHolder.require()).isSameAs(REQUEST);
                                return boundSource.thenApply(ExecutionContextSnapshot.capture()
                                        .bindFunction(ignored -> ExecutionContextHolder.require()));
                            }))
                            .get(2, TimeUnit.SECONDS);

            sdk.submit(() -> ExecutionContextHolder.run(SDK_WORKER, () -> {
                        unboundSource.complete("unbound");
                        boundSource.complete("bound");
                    }))
                    .get(2, TimeUnit.SECONDS);

            assertThat(unboundCallback.get(2, TimeUnit.SECONDS)).contains(SDK_WORKER);
            assertThat(boundCallback.get(2, TimeUnit.SECONDS)).isSameAs(REQUEST);
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }
}
