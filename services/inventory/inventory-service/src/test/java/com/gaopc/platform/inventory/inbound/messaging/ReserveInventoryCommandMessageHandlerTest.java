package com.gaopc.platform.inventory.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaopc.platform.context.Actor;
import com.gaopc.platform.context.ActorType;
import com.gaopc.platform.context.Initiator;
import com.gaopc.platform.context.TenantId;
import com.gaopc.platform.inventory.api.InventoryApi;
import com.gaopc.platform.inventory.api.InventoryReserved;
import com.gaopc.platform.inventory.api.ReserveInventoryCommand;
import com.gaopc.platform.inventory.api.ReserveInventoryLine;
import com.gaopc.platform.message.Destination;
import com.gaopc.platform.message.MessageDescriptor;
import com.gaopc.platform.message.MessageId;
import com.gaopc.platform.message.MessageKind;
import com.gaopc.platform.message.MessageType;
import com.gaopc.platform.message.NonRetryableMessageException;
import com.gaopc.platform.message.SerializedMessage;
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
                new MessageType("com.gaopc.inventory.reserve.v1"),
                URI.create("urn:gaopc:order-service"),
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
