package io.github.ande1922.moduvera.reference.inventory.adapter.outbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class InventoryResultMessagingContractTest {

    @Test
    void resultPublisherEmitsCurrentInventoryV1Identity() {
        var published = new AtomicReference<SerializedMessage>();
        var publisher = new OutboxInventoryResultPublisher(
                published::set,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-30T00:00:01Z"), ZoneOffset.UTC));
        var result = new InventoryReserved(
                "reserve-order-42", 42, Instant.parse("2026-08-30T00:00:01Z"));

        ExecutionContextHolder.run(context(), () -> publisher.publish(result));

        assertThat(published.get().descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.kind().name()).isEqualTo("EVENT");
            assertThat(descriptor.type().value())
                    .isEqualTo("io.github.ande1922.moduvera.reference.inventory.reservation-result.v1");
            assertThat(descriptor.destination().value()).isEqualTo("order.inventory-result");
        });
    }

    private static ExecutionContext context() {
        return ExecutionContext.initiatedBy(
                new TenantId("tenant-a"),
                new Actor(ActorType.SERVICE, "order-service"),
                "corr-order");
    }
}
