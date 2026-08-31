package io.github.ande1922.moduvera.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        } finally {
            inner.close();
            outer.close();
        }
    }

    private static ExecutionContext context(String tenant, String subject, String correlation) {
        Actor actor = new Actor(ActorType.USER, subject);
        return ExecutionContext.initiatedBy(new TenantId(tenant), actor, correlation);
    }
}
