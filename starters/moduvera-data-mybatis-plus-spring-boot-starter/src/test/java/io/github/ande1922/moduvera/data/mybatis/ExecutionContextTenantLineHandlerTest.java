package io.github.ande1922.moduvera.data.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.MissingExecutionContextException;
import io.github.ande1922.moduvera.context.TenantId;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.Test;

class ExecutionContextTenantLineHandlerTest {

    private final ExecutionContextTenantLineHandler handler = new ExecutionContextTenantLineHandler();

    @Test
    void derivesTheSqlTenantFromTheTrustedExecutionContext() {
        Actor actor = new Actor(ActorType.SERVICE, "order-service");
        ExecutionContext context =
                ExecutionContext.initiatedBy(new TenantId("tenant-a"), actor, "corr-data");

        var expression = ExecutionContextHolder.call(context, handler::getTenantId);

        assertThat(expression).isEqualTo(new StringValue("tenant-a"));
    }

    @Test
    void failsBeforeSqlWhenTheExecutionContextIsMissing() {
        assertThatThrownBy(handler::getTenantId)
                .isInstanceOf(MissingExecutionContextException.class);
    }
}
