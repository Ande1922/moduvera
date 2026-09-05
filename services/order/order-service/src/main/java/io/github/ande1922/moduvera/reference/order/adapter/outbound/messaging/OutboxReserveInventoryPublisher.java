package io.github.ande1922.moduvera.reference.order.adapter.outbound.messaging;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.publication.DurablePublication;
import io.github.ande1922.moduvera.reference.order.application.ReserveInventoryPublisher;
import java.net.URI;
import java.time.Clock;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class OutboxReserveInventoryPublisher implements ReserveInventoryPublisher {

    private final DurablePublication outbox;
    private final ObjectMapper json;
    private final Clock clock;

    public OutboxReserveInventoryPublisher(DurablePublication outbox, ObjectMapper json, Clock clock) {
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void publish(ReserveInventoryCommand command) {
        var context = ExecutionContextHolder.require();
        var tenantId = context.requireTenantId();
        var descriptor = new MessageDescriptor(
                new MessageId(command.commandId()),
                MessageKind.valueOf(ReserveInventoryCommand.MESSAGE_KIND),
                new MessageType(ReserveInventoryCommand.MESSAGE_TYPE),
                URI.create("urn:moduvera:reference:order-service"),
                new Destination(ReserveInventoryCommand.DESTINATION),
                clock.instant(),
                tenantId,
                new Actor(ActorType.SERVICE, "order-service"),
                context.correlationId(),
                null,
                context.initiator(),
                Long.toString(command.orderId()));
        try {
            outbox.append(SerializedMessage.json(descriptor, json.writeValueAsString(command)));
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("reserve inventory command cannot be serialized", failure);
        }
    }
}
