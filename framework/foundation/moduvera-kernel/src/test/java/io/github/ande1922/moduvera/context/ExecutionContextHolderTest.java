package io.github.ande1922.moduvera.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ExecutionContextHolderTest {

    private static final ExecutionContext PLATFORM = new ExecutionContext(
            ExecutionScope.platform(),
            new Actor(ActorType.SERVICE, "platform-service"),
            new Initiator(ActorType.USER, "operator"),
            "corr-platform");
    private static final ExecutionContext OUTER = context("tenant-a", "alice", "corr-a");
    private static final ExecutionContext INNER = context("tenant-b", "inventory", "corr-b");
    private static final ExecutionContext SAME_TENANT_OTHER_EXECUTION = new ExecutionContext(
            new TenantId("tenant-a"),
            new Actor(ActorType.SERVICE, "catalog"),
            new Initiator(ActorType.USER, "bob"),
            "corr-a-2");

    @Test
    void distinguishesPlatformTenantAndAbsentWhileRestoringTheCompleteIdentity() {
        assertThat(ExecutionContextHolder.current()).isEmpty();

        ExecutionContextHolder.run(PLATFORM, () -> {
            assertThat(ExecutionContextHolder.require()).isSameAs(PLATFORM);
            ExecutionContextHolder.run(OUTER, () -> {
                assertThat(ExecutionContextHolder.require()).isSameAs(OUTER);
                ExecutionContextHolder.run(SAME_TENANT_OTHER_EXECUTION, () -> {
                    assertThat(ExecutionContextHolder.require()).isSameAs(SAME_TENANT_OTHER_EXECUTION);
                    try (var ignored = ExecutionContextSnapshot.absent().openScope()) {
                        assertThat(ExecutionContextHolder.current()).isEmpty();
                    }
                    assertThat(ExecutionContextHolder.require()).isSameAs(SAME_TENANT_OTHER_EXECUTION);
                });
                assertThat(ExecutionContextHolder.require()).isSameAs(OUTER);
            });
            assertThat(ExecutionContextHolder.require()).isSameAs(PLATFORM);
        });

        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void restoresPlatformAfterExceptionalTenantExecution() {
        var failure = new IllegalStateException("failed tenant execution");

        ExecutionContextHolder.run(PLATFORM, () -> {
            assertThatThrownBy(() -> ExecutionContextHolder.run(INNER, () -> {
                        assertThat(ExecutionContextHolder.require()).isSameAs(INNER);
                        throw failure;
                    }))
                    .isSameAs(failure);
            assertThat(ExecutionContextHolder.require()).isSameAs(PLATFORM);
        });

        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

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
