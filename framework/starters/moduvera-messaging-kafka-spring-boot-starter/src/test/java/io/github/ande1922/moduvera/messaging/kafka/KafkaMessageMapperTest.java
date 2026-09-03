package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;

class KafkaMessageMapperTest {

    @Test
    void mapsEventsToStructuredCloudEventsAndBack() {
        MessageDescriptor descriptor = descriptor(MessageKind.EVENT);
        SerializedMessage original = SerializedMessage.json(descriptor, "{\"orderId\":\"42\"}");

        var mapper = new KafkaMessageMapper();
        var springMessage = mapper.toSpringMessage(original);
        SerializedMessage restored = mapper.fromSpringMessage(springMessage);

        String wireBody = new String(springMessage.getPayload(), StandardCharsets.UTF_8);
        assertThat(springMessage.getHeaders().get(KafkaHeaders.KEY)).isEqualTo("tenant-a:order-42");
        assertThat(springMessage.getHeaders().get("contentType").toString())
                .contains("application/cloudevents+json");
        assertThat(wireBody)
                .contains("\"specversion\":\"1.0\"")
                .contains("\"type\":\"io.github.ande1922.moduvera.reference.inventory.reserve.v1\"")
                .contains("\"data\":{\"orderId\":\"42\"}")
                .doesNotContain("actorpermissions", "schemaversion");
        assertWireContract(restored, original);
    }

    @Test
    void mapsAsyncCommandsToTheirOwnEnvelopeAndBack() {
        MessageDescriptor descriptor = descriptor(MessageKind.ASYNC_COMMAND);
        SerializedMessage original = SerializedMessage.json(descriptor, "{\"orderId\":\"42\"}");

        var mapper = new KafkaMessageMapper();
        var springMessage = mapper.toSpringMessage(original);
        SerializedMessage restored = mapper.fromSpringMessage(springMessage);

        String wireBody = new String(springMessage.getPayload(), StandardCharsets.UTF_8);
        assertThat(springMessage.getHeaders().get("contentType").toString())
                .contains("application/vnd.moduvera.async-command+json");
        assertThat(wireBody)
                .contains("\"kind\":\"async-command\"")
                .contains("\"payload\":{\"orderId\":\"42\"}");
        assertThat(wireBody).doesNotContain("actorpermissions", "schemaversion");
        assertWireContract(restored, original);
    }

    @Test
    void readsLegacyExtraVersionAndPermissionFieldsWithoutTrustingThem() {
        var mapper = new KafkaMessageMapper();
        var current = mapper.toSpringMessage(
                SerializedMessage.json(descriptor(MessageKind.ASYNC_COMMAND), "{}"));
        String legacy = new String(current.getPayload(), StandardCharsets.UTF_8)
                .replace(
                        "\"destination\"",
                        "\"schemaversion\":1,\"actorpermissions\":\"admin:*\",\"destination\"");
        var legacyMessage = MessageBuilder.withPayload(legacy.getBytes(StandardCharsets.UTF_8))
                .copyHeaders(current.getHeaders())
                .build();

        var restored = mapper.fromSpringMessage(legacyMessage);

        assertThat(restored.descriptor().type())
                .isEqualTo(new MessageType("io.github.ande1922.moduvera.reference.inventory.reserve.v1"));
        assertThat(restored.descriptor().actor().permissions()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("io.github.ande1922.moduvera.testing.TenantIdContractValues#validTenantIds")
    void acceptsCanonicalTenantIdsAtTheMessageBoundary(String tenantId) {
        var mapper = new KafkaMessageMapper();
        var springMessage = mapper.toSpringMessage(
                SerializedMessage.json(descriptor(MessageKind.EVENT, tenantId), "{}"));

        SerializedMessage restored = mapper.fromSpringMessage(springMessage);

        assertThat(restored.descriptor().tenantId().value()).isEqualTo(tenantId);
    }

    @ParameterizedTest
    @MethodSource("io.github.ande1922.moduvera.testing.TenantIdContractValues#invalidTenantIds")
    void rejectsInvalidTenantIdsAtTheMessageBoundary(String tenantId) {
        var mapper = new KafkaMessageMapper();
        var valid = mapper.toSpringMessage(
                SerializedMessage.json(descriptor(MessageKind.EVENT), "{}"));
        String invalidEnvelope = new String(valid.getPayload(), StandardCharsets.UTF_8)
                .replace("\"tenantid\":\"tenant-a\"", "\"tenantid\":\"" + tenantId + "\"");
        var message = MessageBuilder.withPayload(invalidEnvelope.getBytes(StandardCharsets.UTF_8))
                .copyHeaders(valid.getHeaders())
                .build();

        assertThatThrownBy(() -> mapper.fromSpringMessage(message))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MessageDescriptor descriptor(MessageKind kind) {
        return descriptor(kind, "tenant-a");
    }

    private static MessageDescriptor descriptor(MessageKind kind, String tenantId) {
        return new MessageDescriptor(
                new MessageId("msg-1"),
                kind,
                new MessageType("io.github.ande1922.moduvera.reference.inventory.reserve.v1"),
                URI.create("urn:service:order"),
                new Destination("inventory.commands"),
                Instant.parse("2026-08-30T00:00:00Z"),
                new TenantId(tenantId),
                new Actor(ActorType.SERVICE, "order-service", Set.of("inventory:reserve")),
                "corr-1",
                new MessageId("request-1"),
                new Initiator(ActorType.USER, "alice"),
                "tenant-a:order-42");
    }

    private static void assertWireContract(SerializedMessage restored, SerializedMessage original) {
        assertThat(restored.descriptor().id()).isEqualTo(original.descriptor().id());
        assertThat(restored.descriptor().kind()).isEqualTo(original.descriptor().kind());
        assertThat(restored.descriptor().type()).isEqualTo(original.descriptor().type());
        assertThat(restored.descriptor().source()).isEqualTo(original.descriptor().source());
        assertThat(restored.descriptor().destination()).isEqualTo(original.descriptor().destination());
        assertThat(restored.descriptor().actor().permissions()).isEmpty();
        assertThat(restored.contentType()).isEqualTo(original.contentType());
        assertThat(restored.payload()).containsExactly(original.payload());
    }
}
