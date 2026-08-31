package io.github.ande1922.moduvera.reference.inventory.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryApi;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ReserveInventoryCommandMessageHandlerTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mapsSerializedPayloadAndInvokesInventoryUseCase() throws Exception {
        var handled = new AtomicReference<ReserveInventoryCommand>();
        InventoryApi inventory = command -> {
            handled.set(command);
            return new InventoryReserved(command.commandId(), command.orderId(), Instant.EPOCH);
        };
        var handler = new ReserveInventoryCommandMessageHandler(inventory, json);
        var command = new ReserveInventoryCommand(
                "reserve-order-42", 42, List.of(new ReserveInventoryLine(7, 2)));

        handler.handle(serialized(json.writeValueAsString(command)));

        assertThat(handled.get()).isEqualTo(command);
    }

    @Test
    void rejectsMalformedPayloadAsNonRetryable() {
        InventoryApi inventory = command -> {
            throw new AssertionError("invalid payload must not invoke the use case");
        };
        var handler = new ReserveInventoryCommandMessageHandler(inventory, json);

        assertThatThrownBy(() -> handler.handle(serialized("{}")))
                .isInstanceOf(NonRetryableMessageException.class)
                .hasMessage("invalid reserve inventory command");
    }

    private static SerializedMessage serialized(String payload) {
        var descriptor = new MessageDescriptor(
                new MessageId("reserve-order-42"),
                MessageKind.ASYNC_COMMAND,
                new MessageType("io.github.ande1922.moduvera.reference.inventory.reserve.v1"),
                URI.create("urn:moduvera:reference:order-service"),
                new Destination("inventory.reserve"),
                Instant.EPOCH,
                new TenantId("tenant-a"),
                new Actor(ActorType.SERVICE, "order-service"),
                "corr-42",
                null,
                new Initiator(ActorType.USER, "alice"),
                "42");
        return SerializedMessage.json(descriptor, payload);
    }
}
