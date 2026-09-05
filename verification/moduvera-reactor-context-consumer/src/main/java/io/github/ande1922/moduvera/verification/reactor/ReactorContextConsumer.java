package io.github.ande1922.moduvera.verification.reactor;

import java.util.Objects;
import java.util.function.Function;

import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.reactor.ReactorExecutionContexts;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Standalone consumer of the public Reactor execution-context templates. */
public final class ReactorContextConsumer {

    private ReactorContextConsumer() {}

    /** Applies one trusted request context to a reusable synchronous mapping template. */
    public static <T, R> Flux<R> process(
            ExecutionContext context,
            Flux<T> source,
            Function<? super T, ? extends R> synchronousMapper) {
        Objects.requireNonNull(context, "context");
        return ReactorExecutionContexts.withContext(
                context, ReactorExecutionContexts.mapInContext(source, synchronousMapper));
    }

    /** Strictly captures the current request when this method is called. */
    public static <T, R> Mono<R> captureAndProcess(
            Mono<T> source, Function<? super T, ? extends R> synchronousMapper) {
        return ReactorExecutionContexts.propagate(
                ReactorExecutionContexts.mapInContext(source, synchronousMapper));
    }
}
