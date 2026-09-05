package io.github.ande1922.moduvera.reactor;

import java.util.Objects;
import java.util.function.Function;

import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionContextSnapshot;
import io.github.ande1922.moduvera.context.MissingExecutionContextException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/** Explicit Reactor Context boundaries for Moduvera execution context. */
public final class ReactorExecutionContexts {

    /** Reserved native Reactor Context key for one trusted {@link ExecutionContext}. */
    public static final String EXECUTION_CONTEXT_KEY =
            "io.github.ande1922.moduvera.execution-context";

    private ReactorExecutionContexts() {}

    /**
     * Writes a trusted context into every subscription to the returned Mono.
     *
     * <p>The returned Publisher is fixed to one logical execution and must not be cached or
     * shared across requests, including requests for the same tenant.
     */
    public static <T> Mono<T> withContext(ExecutionContext context, Mono<T> source) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(source, "source");
        return source.contextWrite(nativeContext -> nativeContext.put(EXECUTION_CONTEXT_KEY, context));
    }

    /**
     * Writes a trusted context into every subscription to the returned Flux.
     *
     * <p>The returned Publisher is fixed to one logical execution and must not be cached or
     * shared across requests, including requests for the same tenant.
     */
    public static <T> Flux<T> withContext(ExecutionContext context, Flux<T> source) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(source, "source");
        return source.contextWrite(nativeContext -> nativeContext.put(EXECUTION_CONTEXT_KEY, context));
    }

    /**
     * Strictly captures the calling thread's context now and writes it to later Mono
     * subscriptions. Capture does not wait for a subscriber or scheduler thread.
     *
     * <p>The returned Publisher is fixed to the captured logical execution and must not be
     * cached or shared across requests.
     */
    public static <T> Mono<T> propagate(Mono<T> source) {
        Objects.requireNonNull(source, "source");
        return withContext(ExecutionContextHolder.require(), source);
    }

    /**
     * Strictly captures the calling thread's context now and writes it to later Flux
     * subscriptions. Capture does not wait for a subscriber or scheduler thread.
     *
     * <p>The returned Publisher is fixed to the captured logical execution and must not be
     * cached or shared across requests.
     */
    public static <T> Flux<T> propagate(Flux<T> source) {
        Objects.requireNonNull(source, "source");
        return withContext(ExecutionContextHolder.require(), source);
    }

    /**
     * Strictly reads the trusted value from a native Reactor Context. This method never falls
     * back to {@link ExecutionContextHolder}; a missing key and a wrongly typed value both fail.
     */
    public static ExecutionContext require(ContextView contextView) {
        Objects.requireNonNull(contextView, "contextView");
        if (!contextView.hasKey(EXECUTION_CONTEXT_KEY)) {
            throw new MissingExecutionContextException();
        }
        Object candidate = contextView.get(EXECUTION_CONTEXT_KEY);
        if (!(candidate instanceof ExecutionContext context)) {
            throw new IllegalStateException("Reactor Context execution context has the wrong type");
        }
        return context;
    }

    /**
     * Restores each subscriber's native context only while the synchronous Mono mapper runs.
     * Missing native state explicitly hides any Holder value on the signal thread. The returned
     * template captures no request identity and may be shared when the source and mapper are also
     * safe to share.
     *
     * <p>Only the mapper call is covered. Publishers returned or started by user code, arbitrary
     * map/flatMap callbacks, blocking work, and detached asynchronous work need their own explicit
     * boundary.
     */
    public static <T, R> Mono<R> mapInContext(
            Mono<T> source, Function<? super T, ? extends R> synchronousMapper) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(synchronousMapper, "synchronousMapper");
        return Mono.deferContextual(contextView -> {
            var snapshot = snapshot(contextView);
            return source.map(snapshot.bindFunction(synchronousMapper::apply));
        });
    }

    /**
     * Restores each subscriber's native context for every synchronous Flux mapper call. Missing
     * native state explicitly hides any Holder value on the signal thread. The returned template
     * captures no request identity and may be shared when the source and mapper are also safe to
     * share.
     *
     * <p>Only mapper calls are covered. This does not install a global Hook, move blocking work to
     * a scheduler, propagate into arbitrary operators or inner Publishers, or make cache/share
     * business data tenant-isolated.
     */
    public static <T, R> Flux<R> mapInContext(
            Flux<T> source, Function<? super T, ? extends R> synchronousMapper) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(synchronousMapper, "synchronousMapper");
        return Flux.deferContextual(contextView -> {
            var snapshot = snapshot(contextView);
            return source.map(snapshot.bindFunction(synchronousMapper::apply));
        });
    }

    private static ExecutionContextSnapshot snapshot(ContextView contextView) {
        if (!contextView.hasKey(EXECUTION_CONTEXT_KEY)) {
            return ExecutionContextSnapshot.absent();
        }
        return ExecutionContextSnapshot.of(require(contextView));
    }
}
