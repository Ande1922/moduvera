package io.github.ande1922.moduvera.verification.reactor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class ReactorContextConsumerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void independentConsumerUsesVersionlessAdapterAcrossARealScheduler() {
        ExecutionContext first = context("tenant-a", "alice", "origin-a", "request-a");
        ExecutionContext second = context("tenant-a", "bob", "origin-b", "request-b");
        Flux<String> source = Flux.just("value").publishOn(Schedulers.parallel());

        List<ExecutionContext> observed = Flux.merge(
                        ReactorContextConsumer.process(first, source, ignored -> readFullContext()),
                        ReactorContextConsumer.process(second, source, ignored -> readFullContext()))
                .collectList()
                .block(TIMEOUT);

        assertThat(observed).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void independentConsumerCapturesBeforeDelayedSubscription() {
        ExecutionContext request = context("tenant-a", "alice", "origin-a", "request-a");
        Mono<ExecutionContext> captured;
        try (var ignored = ExecutionContextHolder.open(request)) {
            captured = ReactorContextConsumer.captureAndProcess(
                    Mono.just("value").publishOn(Schedulers.parallel()), ignoredValue -> readFullContext());
        }

        assertThat(captured.block(TIMEOUT)).isEqualTo(request);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    private static ExecutionContext readFullContext() {
        ExecutionContext context = ExecutionContextHolder.require();
        assertThat(context.scope()).isNotNull();
        assertThat(context.actor()).isNotNull();
        assertThat(context.initiator()).isNotNull();
        assertThat(context.correlationId()).isNotBlank();
        return context;
    }

    private static ExecutionContext context(
            String tenant, String actor, String initiator, String correlation) {
        return new ExecutionContext(
                ExecutionScope.tenant(new TenantId(tenant)),
                new Actor(ActorType.USER, actor, Set.of("notes:read")),
                new Initiator(ActorType.SERVICE, initiator),
                correlation);
    }
}
