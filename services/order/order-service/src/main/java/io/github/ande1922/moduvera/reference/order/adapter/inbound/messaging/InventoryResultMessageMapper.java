package io.github.ande1922.moduvera.reference.order.adapter.inbound.messaging;

import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class InventoryResultMessageMapper {

    private final ObjectMapper json;

    InventoryResultMessageMapper(ObjectMapper json) {
        this.json = json;
    }

    InventoryReservationResult map(byte[] payloadBytes) {
        try {
            JsonNode payload = json.readTree(payloadBytes);
            String commandId = requiredText(payload, "commandId");
            long orderId = payload.get("orderId").asLong();
            if (payload.has("reservedAt")) {
                return new InventoryReserved(
                        commandId, orderId, Instant.parse(requiredText(payload, "reservedAt")));
            }
            return new InventoryRejected(
                    commandId,
                    orderId,
                    json.treeToValue(
                            payload.get("unavailableProductIds"),
                            json.getTypeFactory().constructCollectionType(List.class, Long.class)),
                    Instant.parse(requiredText(payload, "rejectedAt")));
        } catch (RuntimeException invalid) {
            if (invalid instanceof NonRetryableMessageException terminal) {
                throw terminal;
            }
            throw new NonRetryableMessageException("invalid inventory result payload", invalid);
        }
    }

    private static String requiredText(JsonNode payload, String name) {
        JsonNode value = payload.get(name);
        if (value == null || value.asString().isBlank()) {
            throw new NonRetryableMessageException("inventory result is missing " + name);
        }
        return value.asString();
    }
}
