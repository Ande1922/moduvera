package io.github.ande1922.moduvera.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ExecutionContextHolderTest {

    private static final ExecutionContext OUTER = context("tenant-a", "alice", "corr-a");
    private static final ExecutionContext INNER = context("tenant-b", "inventory", "corr-b");

    @Test
    void restoresNestedContextAndClearsAfterTheBoundary() {
        ExecutionContextHolder.run(OUTER, () -> {
            assertThat(ExecutionContextHolder.require()).isSameAs(OUTER);
            ExecutionContextHolder.run(INNER, () -> assertThat(ExecutionContextHolder.require()).isSameAs(INNER));
            assertThat(ExecutionContextHolder.require()).isSameAs(OUTER);
        });

        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void failsClosedWhenNoContextExists() {
        assertThatThrownBy(ExecutionContextHolder::require)
                .isInstanceOf(MissingExecutionContextException.class);
    }

    @Test
    void rejectsOutOfOrderScopeClosure() {
        ExecutionContextHolder.Scope outer = ExecutionContextHolder.open(OUTER);
        ExecutionContextHolder.Scope inner = ExecutionContextHolder.open(INNER);
        try {
            assertThatThrownBy(outer::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("reverse order");
            assertThat(ExecutionContextHolder.require()).isSameAs(INNER);
        } finally {
            inner.close();
            outer.close();
        }
    }

    @Test
    void rejectsNullWithoutChangingTheCurrentContext() {
        ExecutionContextHolder.run(OUTER, () -> {
            assertThatThrownBy(() -> ExecutionContextHolder.open(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("context");
            assertThat(ExecutionContextHolder.require()).isSameAs(OUTER);
        });
    }

    @Test
    void rejectsOutOfOrderClosureWhenNestedScopesInstallTheSameContextInstance() {
        ExecutionContextHolder.Scope outer = ExecutionContextHolder.open(OUTER);
        ExecutionContextHolder.Scope inner = ExecutionContextHolder.open(OUTER);
        try {
            assertThatThrownBy(outer::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("reverse order");
            assertThat(ExecutionContextHolder.require()).isSameAs(OUTER);
        } finally {
            inner.close();
            outer.close();
        }
    }

    @Test
    void rejectsCrossThreadClosureWithoutChangingEitherThread() throws Exception {
        ExecutionContextHolder.Scope scope = ExecutionContextHolder.open(OUTER);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var observation = worker.submit(() -> ExecutionContextHolder.call(INNER, () -> {
                assertThatThrownBy(scope::close)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("owning thread");
                return ExecutionContextHolder.require();
            }));

            assertThat(observation.get()).isSameAs(INNER);
            assertThat(ExecutionContextHolder.require()).isSameAs(OUTER);
        } finally {
            scope.close();
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void repeatsCloseIdempotentlyAfterAValidClose() {
        try (ExecutionContextHolder.Scope scope = ExecutionContextHolder.open(OUTER)) {
            scope.close();
            scope.close();
        }

        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    private static ExecutionContext context(String tenant, String subject, String correlation) {
        Actor actor = new Actor(ActorType.USER, subject);
        return ExecutionContext.initiatedBy(new TenantId(tenant), actor, correlation);
    }
}
