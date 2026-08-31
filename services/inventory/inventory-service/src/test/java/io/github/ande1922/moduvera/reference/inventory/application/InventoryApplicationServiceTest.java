package io.github.ande1922.moduvera.reference.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import io.github.ande1922.moduvera.reference.inventory.infrastructure.memory.InMemoryInventoryStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InventoryApplicationServiceTest {

    @Test
    void repeatedCommandReturnsTheSameResultWithoutReservingTwice() {
        TenantId tenant = new TenantId("tenant-a");
        var store = new InMemoryInventoryStore();
        ExecutionContext tenantContext = context(tenant);
        ExecutionContextHolder.run(tenantContext, () -> store.setAvailable(7, 5));
        var service = new InventoryApplicationService(
                store,
                new UseCaseAuthorizer(),
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC),
                ignored -> {});
        var command = new ReserveInventoryCommand("reserve-order-42", 42, List.of(new ReserveInventoryLine(7, 2)));

        var first = ExecutionContextHolder.call(tenantContext, () -> service.reserve(command));
        var duplicate = ExecutionContextHolder.call(tenantContext, () -> service.reserve(command));

        assertThat(first).isInstanceOf(InventoryReserved.class).isEqualTo(duplicate);
        assertThat(ExecutionContextHolder.call(tenantContext, () -> store.available(7))).isEqualTo(3);
        assertThat(ExecutionContextHolder.call(
                        context(new TenantId("tenant-b")), () -> store.available(7)))
                .isZero();
    }

    private static ExecutionContext context(TenantId tenant) {
        Actor actor = new Actor(
                ActorType.SERVICE, "order-service", Set.of(InventoryApplicationService.RESERVE.value()));
        return ExecutionContext.initiatedBy(tenant, actor, "corr-inventory");
    }
}
