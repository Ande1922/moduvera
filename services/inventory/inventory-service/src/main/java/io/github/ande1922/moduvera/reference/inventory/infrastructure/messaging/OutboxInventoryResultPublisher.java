package io.github.ande1922.moduvera.reference.inventory.infrastructure.messaging;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryResultPublisher;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.publication.DurablePublication;
import java.net.URI;
import java.time.Clock;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class OutboxInventoryResultPublisher implements InventoryResultPublisher {

    private final DurablePublication outbox;
    private final ObjectMapper json;
    private final Clock clock;

    public OutboxInventoryResultPublisher(DurablePublication outbox, ObjectMapper json, Clock clock) {
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void publish(InventoryReservationResult result) {
        var context = ExecutionContextHolder.require();
        String messageId = "inventory-result:" + result.commandId();
        var descriptor = new MessageDescriptor(
                new MessageId(messageId),
                MessageKind.valueOf(InventoryReservationResult.MESSAGE_KIND),
                new MessageType(InventoryReservationResult.MESSAGE_TYPE),
                URI.create("urn:moduvera:reference:inventory-service"),
                new Destination(InventoryReservationResult.DESTINATION),
                clock.instant(),
                context.tenantId(),
                new Actor(ActorType.SERVICE, "inventory-service"),
                context.correlationId(),
                new MessageId(result.commandId()),
                context.initiator(),
                context.tenantId().value() + ':' + result.orderId());
        try {
            outbox.append(SerializedMessage.json(descriptor, json.writeValueAsString(result)));
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("inventory result cannot be serialized", failure);
        }
    }
}
