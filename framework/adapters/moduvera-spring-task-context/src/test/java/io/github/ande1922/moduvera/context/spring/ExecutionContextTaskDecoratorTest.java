package io.github.ande1922.moduvera.context.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionContextSnapshot;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.Initiator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.support.TaskExecutorAdapter;

class ExecutionContextTaskDecoratorTest {

    private final ExecutionContextTaskDecorator decorator = new ExecutionContextTaskDecorator();

    @Test
    void capturesEachTaskWhenItIsDecoratedAndRestoresInlineCaller() {
        ExecutionContext submitted = context("submitted", "initiator-a", "correlation-a");
        ExecutionContext caller = context("caller", "initiator-b", "correlation-b");
        AtomicReference<Optional<ExecutionContext>> observed = new AtomicReference<>();

        ExecutionContextSnapshot.BoundRunnable decorated;
        try (var ignored = ExecutionContextHolder.open(submitted)) {
            decorated = decorator.decorate(() -> observed.set(ExecutionContextHolder.current()));
        }

        try (var ignored = ExecutionContextHolder.open(caller)) {
            decorated.run();
            assertThat(observed.get()).contains(submitted);
            assertThat(ExecutionContextHolder.require()).isEqualTo(caller);
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void capturesAbsentForEveryRealSpringTaskSubmission() {
        AtomicReference<Optional<ExecutionContext>> observed = new AtomicReference<>();
        TaskExecutorAdapter inlineExecutor = new TaskExecutorAdapter(Runnable::run);
        inlineExecutor.setTaskDecorator(decorator);

        ExecutionContext caller = context("caller", "initiator", "correlation");
        inlineExecutor.execute(() -> observed.set(ExecutionContextHolder.current()));

        assertThat(observed.get()).isEmpty();
        try (var ignored = ExecutionContextHolder.open(caller)) {
            inlineExecutor.execute(() -> observed.set(ExecutionContextHolder.current()));
            assertThat(observed.get()).contains(caller);
            assertThat(ExecutionContextHolder.require()).isEqualTo(caller);
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void preservesDelegateFailureAndRestoresTheInvokingThread() {
        ExecutionContext submitted = context("submitted", "initiator-a", "correlation-a");
        ExecutionContext caller = context("caller", "initiator-b", "correlation-b");
        IllegalStateException failure = new IllegalStateException("task failed");

        Runnable decorated;
        try (var ignored = ExecutionContextHolder.open(submitted)) {
            decorated = decorator.decorate(() -> {
                assertThat(ExecutionContextHolder.require()).isEqualTo(submitted);
                throw failure;
            });
        }

        try (var ignored = ExecutionContextHolder.open(caller)) {
            assertThatThrownBy(decorated::run).isSameAs(failure);
            assertThat(ExecutionContextHolder.require()).isEqualTo(caller);
        }
    }

    @Test
    void rejectsNullTaskWithoutChangingTheHolder() {
        ExecutionContext caller = context("caller", "initiator", "correlation");

        try (var ignored = ExecutionContextHolder.open(caller)) {
            assertThatThrownBy(() -> decorator.decorate(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("task");
            assertThat(ExecutionContextHolder.require()).isEqualTo(caller);
        }
    }

    private static ExecutionContext context(String actor, String initiator, String correlation) {
        return new ExecutionContext(
                ExecutionScope.platform(),
                new Actor(ActorType.USER, actor),
                new Initiator(ActorType.SERVICE, initiator),
                correlation);
    }
}
