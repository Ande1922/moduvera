package io.github.ande1922.moduvera.testing.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

public interface InboundMessageContractTck {

    InboundMessageContractProbe newInboundMessageContractProbe();

    @Test
    default void acceptsDeclaredContractAndEstablishesConsumerOwnedExecutionContext() {
        InboundMessageContractProbe probe = newInboundMessageContractProbe();
        SerializedMessage valid = probe.validMessage();
        MessageDescriptor descriptor = valid.descriptor();
        assertDoesNotThrow(() -> probe.expectedContract().validate(descriptor));

        Actor forgedWireActor = new Actor(
                descriptor.actor().type(),
                descriptor.actor().subjectId(),
                Set.of("contract-tck:forged-wire-permission"));
        assertNotEquals(
                forgedWireActor.permissions(),
                probe.expectedContract().executionActor().permissions(),
                "the TCK fixture must prove that wire permissions are not trusted");

        int before = probe.applicationInvocations().getAsInt();
        probe.deliver().accept(withActor(valid, forgedWireActor));

        assertEquals(before + 1, probe.applicationInvocations().getAsInt());
        assertEquals(
                new ExecutionContext(
                        descriptor.tenantId(),
                        probe.expectedContract().executionActor(),
                        descriptor.initiator(),
                        descriptor.correlationId()),
                probe.observedApplicationContext().get());
        assertFalse(ExecutionContextHolder.current().isPresent(), "message context must be cleared");
        probe.assertAcceptedApplicationState().run();
    }

    @Test
    default void rejectsEachContractIdentityMismatchBeforeApplicationInvocation() {
        InboundMessageContractProbe probe = newInboundMessageContractProbe();
        int before = probe.applicationInvocations().getAsInt();

        contractMismatches(probe.validMessage()).forEach((dimension, invalid) -> {
            NonRetryableMessageException failure = assertThrows(
                    NonRetryableMessageException.class,
                    () -> probe.deliver().accept(invalid),
                    () -> "expected rejection for mismatched " + dimension);
            assertEquals(
                    "message does not match the expected inbound contract",
                    failure.getMessage(),
                    () -> "unexpected rejection for mismatched " + dimension);
            assertEquals(
                    before,
                    probe.applicationInvocations().getAsInt(),
                    () -> "Application invoked for mismatched " + dimension);
            assertFalse(
                    ExecutionContextHolder.current().isPresent(),
                    () -> "message context leaked for mismatched " + dimension);
        });
        assertNull(
                probe.observedApplicationContext().get(),
                "rejected contracts must not establish an Application context");
    }

    private static Map<String, SerializedMessage> contractMismatches(SerializedMessage valid) {
        MessageDescriptor descriptor = valid.descriptor();
        MessageKind otherKind = java.util.Arrays.stream(MessageKind.values())
                .filter(kind -> kind != descriptor.kind())
                .findFirst()
                .orElseThrow();
        var mismatches = new LinkedHashMap<String, SerializedMessage>();
        mismatches.put(
                "kind",
                withContract(
                        valid,
                        otherKind,
                        descriptor.type(),
                        descriptor.source(),
                        descriptor.destination()));
        mismatches.put(
                "type",
                withContract(
                        valid,
                        descriptor.kind(),
                        differentMessageType(descriptor.type()),
                        descriptor.source(),
                        descriptor.destination()));
        mismatches.put(
                "source",
                withContract(
                        valid,
                        descriptor.kind(),
                        descriptor.type(),
                        differentSource(descriptor.source()),
                        descriptor.destination()));
        mismatches.put(
                "destination",
                withContract(
                        valid,
                        descriptor.kind(),
                        descriptor.type(),
                        descriptor.source(),
                        differentDestination(descriptor.destination())));
        return mismatches;
    }

    private static MessageType differentMessageType(MessageType expected) {
        MessageType candidate = new MessageType("contract-tck-mismatch.v1");
        return candidate.equals(expected)
                ? new MessageType("contract-tck-alternate.v1")
                : candidate;
    }

    private static URI differentSource(URI expected) {
        URI candidate = URI.create("urn:moduvera:contract-tck:unexpected-source");
        return candidate.equals(expected)
                ? URI.create("urn:moduvera:contract-tck:alternate-source")
                : candidate;
    }

    private static Destination differentDestination(Destination expected) {
        Destination candidate = new Destination("contract-tck.mismatch");
        return candidate.equals(expected)
                ? new Destination("contract-tck.alternate")
                : candidate;
    }

    private static SerializedMessage withActor(SerializedMessage original, Actor actor) {
        MessageDescriptor descriptor = original.descriptor();
        return withDescriptor(
                original,
                new MessageDescriptor(
                        descriptor.id(),
                        descriptor.kind(),
                        descriptor.type(),
                        descriptor.source(),
                        descriptor.destination(),
                        descriptor.time(),
                        descriptor.tenantId(),
                        actor,
                        descriptor.correlationId(),
                        descriptor.causationId(),
                        descriptor.initiator(),
                        descriptor.partitionKey()));
    }

    private static SerializedMessage withContract(
            SerializedMessage original,
            MessageKind kind,
            MessageType type,
            URI source,
            Destination destination) {
        MessageDescriptor descriptor = original.descriptor();
        return withDescriptor(
                original,
                new MessageDescriptor(
                        descriptor.id(),
                        kind,
                        type,
                        source,
                        destination,
                        descriptor.time(),
                        descriptor.tenantId(),
                        descriptor.actor(),
                        descriptor.correlationId(),
                        descriptor.causationId(),
                        descriptor.initiator(),
                        descriptor.partitionKey()));
    }

    private static SerializedMessage withDescriptor(
            SerializedMessage original, MessageDescriptor descriptor) {
        return new SerializedMessage(descriptor, original.contentType(), original.payload());
    }
}
