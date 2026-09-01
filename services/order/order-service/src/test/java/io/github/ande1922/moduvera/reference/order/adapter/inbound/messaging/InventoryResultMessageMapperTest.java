package io.github.ande1922.moduvera.reference.order.adapter.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class InventoryResultMessageMapperTest {

    private final InventoryResultMessageMapper mapper =
            new InventoryResultMessageMapper(new ObjectMapper());

    @Test
    void mapsReservedAndRejectedResults() {
        var reserved = mapper.map(message(
                "{\"commandId\":\"reserve-order-41\",\"orderId\":41,"
                        + "\"reservedAt\":\"2026-08-30T00:00:01Z\"}"));
        var rejected = mapper.map(message(
                "{\"commandId\":\"reserve-order-42\",\"orderId\":42,"
                        + "\"unavailableProductIds\":[7,9],"
                        + "\"rejectedAt\":\"2026-08-30T00:00:02Z\"}"));

        assertThat(reserved).isEqualTo(new InventoryReserved(
                "reserve-order-41", 41, Instant.parse("2026-08-30T00:00:01Z")));
        assertThat(rejected).isEqualTo(new InventoryRejected(
                "reserve-order-42",
                42,
                java.util.List.of(7L, 9L),
                Instant.parse("2026-08-30T00:00:02Z")));
    }

    @Test
    void rejectsMalformedPayloadAsNonRetryable() {
        assertThatThrownBy(() -> mapper.map(message(
                        "{\"orderId\":41,\"reservedAt\":\"2026-08-30T00:00:01Z\"}")))
                .isInstanceOf(NonRetryableMessageException.class)
                .hasMessage("inventory result is missing commandId")
                .hasNoCause();
    }

    private static SerializedMessage message(String payload) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId("result-1"),
                        MessageKind.EVENT,
                        new MessageType("io.github.ande1922.moduvera.reference.inventory.reservation-result.v1"),
                        URI.create("urn:moduvera:reference:inventory-service"),
                        new Destination("order.inventory-result"),
                        Instant.parse("2026-08-30T00:00:02Z"),
                        new TenantId("tenant-a"),
                        new Actor(
                                ActorType.SERVICE,
                                "inventory-service",
                                Set.of("order:apply-inventory-result")),
                        "corr-1",
                        new MessageId("reserve-order-41"),
                        new Initiator(ActorType.USER, "alice"),
                        "tenant-a:41"),
                payload);
    }
}
