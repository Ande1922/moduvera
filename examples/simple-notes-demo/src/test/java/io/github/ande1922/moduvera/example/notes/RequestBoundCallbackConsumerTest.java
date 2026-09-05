package io.github.ande1922.moduvera.example.notes;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionContextSnapshot;
import io.github.ande1922.moduvera.context.ExecutionContextSnapshot.BoundFunction;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RequestBoundCallbackConsumerTest {

    private static final ExecutionContext FIRST_REQUEST = request("api-a", "alice", "corr-a");
    private static final ExecutionContext SECOND_REQUEST = request("api-b", "bob", "corr-b");
    private static final ExecutionContext WORKER = new ExecutionContext(
            ExecutionScope.platform(),
            new Actor(ActorType.SYSTEM, "sdk-worker"),
            new Initiator(ActorType.SYSTEM, "sdk"),
            "worker-corr");

    @Test
    void sdkDriverRetainsSeparateRequestCallbacksUntilLaterTrigger() {
        Function<String, Observation> processor =
                value -> new Observation(value, ExecutionContextHolder.require());
        CallbackSdkDriver sdk = new CallbackSdkDriver();

        ExecutionContextHolder.run(FIRST_REQUEST, () -> sdk.register(
                "first", ExecutionContextSnapshot.capture().bindFunction(processor)));
        ExecutionContextHolder.run(SECOND_REQUEST, () -> sdk.register(
                "second", ExecutionContextSnapshot.capture().bindFunction(processor)));
        assertThat(ExecutionContextHolder.current()).isEmpty();

        ExecutionContextHolder.run(WORKER, () -> {
            assertThat(sdk.trigger("first", "first-payload"))
                    .isEqualTo(new Observation("first-payload", FIRST_REQUEST));
            assertThat(ExecutionContextHolder.require()).isSameAs(WORKER);
            assertThat(sdk.trigger("second", "second-payload"))
                    .isEqualTo(new Observation("second-payload", SECOND_REQUEST));
            assertThat(ExecutionContextHolder.require()).isSameAs(WORKER);
        });
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void longLivedListenerRebuildsOneBindingFromEachTrustedEvent() {
        List<Observation> observations = new ArrayList<>();
        Consumer<String> processor = value -> observations.add(
                new Observation(value, ExecutionContextHolder.require()));
        LongLivedListener listener = new LongLivedListener(processor);

        ExecutionContextHolder.run(WORKER, () -> {
            listener.onEvent(new TrustedEvent(FIRST_REQUEST, "first"));
            listener.onEvent(new TrustedEvent(SECOND_REQUEST, "second"));
            assertThat(ExecutionContextHolder.require()).isSameAs(WORKER);
        });

        assertThat(observations)
                .containsExactly(
                        new Observation("first", FIRST_REQUEST),
                        new Observation("second", SECOND_REQUEST));
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    private static ExecutionContext request(String actor, String initiator, String correlation) {
        return new ExecutionContext(
                ExecutionScope.tenant(new TenantId("same-tenant")),
                new Actor(ActorType.SERVICE, actor),
                new Initiator(ActorType.USER, initiator),
                correlation);
    }

    private record TrustedEvent(ExecutionContext context, String payload) {}

    private record Observation(String payload, ExecutionContext context) {}

    private static final class CallbackSdkDriver {

        private final Map<String, BoundFunction<String, Observation>> callbacks = new HashMap<>();

        private void register(String requestId, BoundFunction<String, Observation> callback) {
            callbacks.put(requestId, callback);
        }

        private Observation trigger(String requestId, String payload) {
            return callbacks.get(requestId).apply(payload);
        }
    }

    private static final class LongLivedListener {

        private final Consumer<String> processor;

        private LongLivedListener(Consumer<String> processor) {
            this.processor = processor;
        }

        private void onEvent(TrustedEvent event) {
            ExecutionContextSnapshot.of(event.context())
                    .bindConsumer(processor)
                    .accept(event.payload());
        }
    }
}
