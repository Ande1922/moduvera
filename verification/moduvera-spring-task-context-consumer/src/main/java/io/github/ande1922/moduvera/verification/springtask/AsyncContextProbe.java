package io.github.ande1922.moduvera.verification.springtask;

import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;

/** Public consumer methods used to prove actual Spring Async proxy routing. */
public class AsyncContextProbe {

    @Async(SpringTaskContextConsumer.PROPAGATING_EXECUTOR)
    public CompletableFuture<Optional<ExecutionContext>> throughPropagatingExecutor() {
        return CompletableFuture.completedFuture(ExecutionContextHolder.current());
    }

    @Async(SpringTaskContextConsumer.PLAIN_EXECUTOR)
    public CompletableFuture<Optional<ExecutionContext>> throughPlainExecutor() {
        return CompletableFuture.completedFuture(ExecutionContextHolder.current());
    }

    @Async(SpringTaskContextConsumer.PROPAGATING_EXECUTOR)
    public CompletableFuture<Void> fail(String message) {
        throw new IllegalStateException(message);
    }
}
