package io.github.ande1922.moduvera.authorization;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UseCaseAuthorizerTest {

    private static final PermissionCode CREATE_ORDER = new PermissionCode("order:create");

    @Test
    void authorizesAtTheUseCaseBoundaryFromTheTrustedExecutionContext() {
        Actor actor = new Actor(ActorType.USER, "alice", Set.of(CREATE_ORDER.value()));
        ExecutionContext context = ExecutionContext.initiatedBy(new TenantId("tenant-a"), actor, "corr-1");

        assertThatCode(() -> ExecutionContextHolder.run(context, () -> new UseCaseAuthorizer().require(CREATE_ORDER)))
                .doesNotThrowAnyException();
    }

    @Test
    void failsClosedWhenPermissionOrContextIsMissing() {
        Actor actor = new Actor(ActorType.USER, "bob");
        ExecutionContext context = ExecutionContext.initiatedBy(new TenantId("tenant-a"), actor, "corr-2");

        assertThatThrownBy(() -> ExecutionContextHolder.run(
                        context, () -> new UseCaseAuthorizer().require(CREATE_ORDER)))
                .isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> new UseCaseAuthorizer().require(CREATE_ORDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("context");
    }
}
