package io.github.ande1922.moduvera.reference.order.adapter.outbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class InventoryMessagingContractTest {

    @Test
    void reservePublisherEmitsCurrentInventoryV1Identity() {
        var published = new AtomicReference<SerializedMessage>();
        var publisher = new OutboxReserveInventoryPublisher(
                published::set,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
        var command = new ReserveInventoryCommand(
                "reserve-order-42", 42, List.of(new ReserveInventoryLine(7, 2)));

        ExecutionContextHolder.run(context(), () -> publisher.publish(command));

        assertThat(published.get().descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.kind().name()).isEqualTo("ASYNC_COMMAND");
            assertThat(descriptor.type().value())
                    .isEqualTo("io.github.ande1922.moduvera.reference.inventory.reserve.v1");
            assertThat(descriptor.destination().value()).isEqualTo("inventory.reserve");
        });
    }

    private static ExecutionContext context() {
        return ExecutionContext.initiatedBy(
                new TenantId("tenant-a"), new Actor(ActorType.USER, "alice"), "corr-order");
    }
}
