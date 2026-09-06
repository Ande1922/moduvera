package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.TraceContextCarrier;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;

class KafkaMessageMapperTest {

    private static final String TRACE_PARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String UNSAMPLED_TRACE_PARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00";
    private static final String TRACE_STATE = "vendor=value";
    private static final TraceContextCarrier CREATION_CONTEXT =
            new TraceContextCarrier(TRACE_PARENT, TRACE_STATE);

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
                .doesNotContain("actorpermissions", "schemaversion", "traceparent", "tracestate");
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
        assertThat(wireBody)
                .doesNotContain("actorpermissions", "schemaversion", "traceparent", "tracestate");
        assertWireContract(restored, original);
    }

    @ParameterizedTest
    @MethodSource("messageKinds")
    void roundTripsAnExplicitCreationContextWithoutChangingTheMessageContract(MessageKind kind) {
        var mapper = new KafkaMessageMapper();
        SerializedMessage original = SerializedMessage.json(
                descriptor(kind, "tenant-a", CREATION_CONTEXT), "{\"orderId\":\"42\"}");

        var springMessage = mapper.toSpringMessage(original);
        SerializedMessage restored = mapper.fromSpringMessage(springMessage);

        assertThat(new String(springMessage.getPayload(), StandardCharsets.UTF_8))
                .contains("\"traceparent\":\"" + TRACE_PARENT + "\"")
                .contains("\"tracestate\":\"" + TRACE_STATE + "\"");
        assertThat(restored.descriptor().creationContext()).isEqualTo(CREATION_CONTEXT);
        assertThat(restored.descriptor().type()).isEqualTo(original.descriptor().type());
        assertThat(restored.descriptor().correlationId()).isEqualTo("corr-1");
        assertWireContract(restored, original);
    }

    @ParameterizedTest
    @MethodSource("compatibilityFixtures")
    void readsFixedLegacyAndCreationEnvelopeFixtures(CompatibilityFixture fixture) throws IOException {
        var message = MessageBuilder.withPayload(resourceBytes(fixture.resource()))
                .setHeader("contentType", fixture.contentType())
                .build();

        SerializedMessage restored = new KafkaMessageMapper().fromSpringMessage(message);

        assertThat(restored.descriptor().kind()).isEqualTo(fixture.kind());
        assertThat(restored.descriptor().type().value()).isEqualTo(fixture.messageType());
        assertThat(restored.descriptor().correlationId()).isEqualTo("legacy-correlation-01");
        assertThat(restored.descriptor().creationContext()).isEqualTo(fixture.creationContext());
        assertThat(restored.descriptor().actor().permissions()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("messageKinds")
    void discardsAnInvalidCreationRelationshipWithoutPoisoningTheBusinessMessage(MessageKind kind) {
        var mapper = new KafkaMessageMapper();
        var invalidCreation = new TraceContextCarrier("not-a-w3c-traceparent", "not-a-tracestate");
        SerializedMessage original = SerializedMessage.json(
                descriptor(kind, "tenant-a", invalidCreation), "{\"orderId\":\"42\"}");

        SerializedMessage restored = mapper.fromSpringMessage(mapper.toSpringMessage(original));

        assertThat(restored.descriptor().creationContext()).isNull();
        assertThat(restored.descriptor().correlationId()).isEqualTo("corr-1");
        assertThat(restored.payload()).containsExactly(original.payload());
    }

    @ParameterizedTest
    @MethodSource("outOfBoundsCreationMutations")
    void dropsOutOfBoundsCreationValuesWithoutChangingCorrelation(CreationMutation mutation)
            throws IOException {
        var json = new tools.jackson.databind.ObjectMapper();
        var envelope = json.readTree(resourceBytes(
                        "messaging/compatibility/v1/event-with-creation.json"))
                .asObject();
        mutation.apply().accept(envelope);
        var message = MessageBuilder.withPayload(json.writeValueAsBytes(envelope))
                .setHeader("contentType", KafkaMessageMapper.STRUCTURED_CLOUD_EVENT)
                .build();

        SerializedMessage restored = new KafkaMessageMapper().fromSpringMessage(message);

        assertThat(restored.descriptor().creationContext()).isEqualTo(mutation.expected());
        assertThat(restored.descriptor().correlationId()).isEqualTo("legacy-correlation-01");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonStringCreationMutations")
    void ignoresNonStringCreationMetadataWithoutPoisoningBusinessMessage(
            NonStringCreationMutation mutation) throws IOException {
        var json = new tools.jackson.databind.ObjectMapper();
        var envelope = json.readTree(resourceBytes(mutation.resource())).asObject();
        byte[] expectedPayload = json.writeValueAsBytes(
                envelope.get(mutation.kind() == MessageKind.EVENT ? "data" : "payload"));
        mutation.apply().accept(envelope);
        var message = MessageBuilder.withPayload(json.writeValueAsBytes(envelope))
                .setHeader("contentType", mutation.contentType())
                .build();

        SerializedMessage restored = new KafkaMessageMapper().fromSpringMessage(message);

        assertThat(restored.descriptor().creationContext()).isEqualTo(mutation.expected());
        assertThat(restored.descriptor().correlationId()).isEqualTo("legacy-correlation-01");
        assertThat(restored.payload()).containsExactly(expectedPayload);
    }

    @Test
    void retainsAnUnsampledParentAndAppliesStandardInvalidTraceStateSemantics() {
        var mapper = new KafkaMessageMapper();
        var creation = new TraceContextCarrier(UNSAMPLED_TRACE_PARENT, "invalid");
        SerializedMessage original = SerializedMessage.json(
                descriptor(MessageKind.EVENT, "tenant-a", creation), "{}");

        SerializedMessage restored = mapper.fromSpringMessage(mapper.toSpringMessage(original));

        assertThat(restored.descriptor().creationContext())
                .isEqualTo(new TraceContextCarrier(UNSAMPLED_TRACE_PARENT, null));
    }

    @Test
    void retainsLegacyCorrelationThatStillFailsTheExistingExecutionContract() throws IOException {
        String oldEnvelope = new String(
                        resourceBytes("messaging/compatibility/v1/event-without-creation.json"),
                        StandardCharsets.UTF_8)
                .replace("legacy-correlation-01", "legacy correlation 01");
        var message = MessageBuilder.withPayload(oldEnvelope.getBytes(StandardCharsets.UTF_8))
                .setHeader("contentType", KafkaMessageMapper.STRUCTURED_CLOUD_EVENT)
                .build();

        SerializedMessage restored = new KafkaMessageMapper().fromSpringMessage(message);

        assertThat(restored.descriptor().correlationId()).isEqualTo("legacy correlation 01");
        assertThatThrownBy(() -> new ExecutionContext(
                        restored.descriptor().tenantId(),
                        new Actor(ActorType.SERVICE, "consumer"),
                        restored.descriptor().initiator(),
                        restored.descriptor().correlationId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("correlationId must be 1-128 portable identifier characters");
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

    @Test
    void rejectsMissingTenantAndExecutionIdentityAttributes() throws Exception {
        var mapper = new KafkaMessageMapper();
        var json = new tools.jackson.databind.ObjectMapper();
        var valid = mapper.toSpringMessage(
                SerializedMessage.json(descriptor(MessageKind.ASYNC_COMMAND), "{}"));

        for (String attribute : List.of(
                "tenantid", "correlationid", "initiatortype", "initiatorsubject")) {
            var envelope = json.readTree(valid.getPayload()).asObject();
            envelope.remove(attribute);
            var missingAttribute = MessageBuilder.withPayload(json.writeValueAsBytes(envelope))
                    .copyHeaders(valid.getHeaders())
                    .build();

            assertThatThrownBy(() -> mapper.fromSpringMessage(missingAttribute))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("missing message attribute: " + attribute);
        }
    }

    private static MessageDescriptor descriptor(MessageKind kind) {
        return descriptor(kind, "tenant-a");
    }

    private static MessageDescriptor descriptor(MessageKind kind, String tenantId) {
        return descriptor(kind, tenantId, null);
    }

    private static MessageDescriptor descriptor(
            MessageKind kind, String tenantId, TraceContextCarrier creationContext) {
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
                "tenant-a:order-42",
                creationContext);
    }

    private static List<MessageKind> messageKinds() {
        return List.of(MessageKind.EVENT, MessageKind.ASYNC_COMMAND);
    }

    private static List<CompatibilityFixture> compatibilityFixtures() {
        return List.of(
                new CompatibilityFixture(
                        "messaging/compatibility/v1/event-without-creation.json",
                        KafkaMessageMapper.STRUCTURED_CLOUD_EVENT,
                        MessageKind.EVENT,
                        "io.github.ande1922.moduvera.compatibility.event.v1",
                        null),
                new CompatibilityFixture(
                        "messaging/compatibility/v1/event-with-creation.json",
                        KafkaMessageMapper.STRUCTURED_CLOUD_EVENT,
                        MessageKind.EVENT,
                        "io.github.ande1922.moduvera.compatibility.event.v1",
                        CREATION_CONTEXT),
                new CompatibilityFixture(
                        "messaging/compatibility/v1/command-without-creation.json",
                        KafkaMessageMapper.ASYNC_COMMAND,
                        MessageKind.ASYNC_COMMAND,
                        "io.github.ande1922.moduvera.compatibility.command.v1",
                        null),
                new CompatibilityFixture(
                        "messaging/compatibility/v1/command-with-creation.json",
                        KafkaMessageMapper.ASYNC_COMMAND,
                        MessageKind.ASYNC_COMMAND,
                        "io.github.ande1922.moduvera.compatibility.command.v1",
                        CREATION_CONTEXT));
    }

    private static List<CreationMutation> outOfBoundsCreationMutations() {
        return List.of(
                new CreationMutation(envelope -> envelope.put("traceparent", " "), null),
                new CreationMutation(envelope -> envelope.put("traceparent", "x".repeat(513)), null),
                new CreationMutation(
                        envelope -> envelope.put("tracestate", " "),
                        new TraceContextCarrier(TRACE_PARENT, null)),
                new CreationMutation(
                        envelope -> envelope.put("tracestate", "x".repeat(513)),
                        new TraceContextCarrier(TRACE_PARENT, null)));
    }

    private static List<NonStringCreationMutation> nonStringCreationMutations() {
        return List.of(
                new NonStringCreationMutation(
                        "event object parent",
                        "messaging/compatibility/v1/event-with-creation.json",
                        KafkaMessageMapper.STRUCTURED_CLOUD_EVENT,
                        MessageKind.EVENT,
                        envelope -> envelope.putObject("traceparent"),
                        null),
                new NonStringCreationMutation(
                        "command array parent",
                        "messaging/compatibility/v1/command-with-creation.json",
                        KafkaMessageMapper.ASYNC_COMMAND,
                        MessageKind.ASYNC_COMMAND,
                        envelope -> envelope.putArray("traceparent"),
                        null),
                new NonStringCreationMutation(
                        "event numeric parent",
                        "messaging/compatibility/v1/event-with-creation.json",
                        KafkaMessageMapper.STRUCTURED_CLOUD_EVENT,
                        MessageKind.EVENT,
                        envelope -> envelope.put("traceparent", 42),
                        null),
                new NonStringCreationMutation(
                        "command boolean parent",
                        "messaging/compatibility/v1/command-with-creation.json",
                        KafkaMessageMapper.ASYNC_COMMAND,
                        MessageKind.ASYNC_COMMAND,
                        envelope -> envelope.put("traceparent", true),
                        null),
                new NonStringCreationMutation(
                        "event object state",
                        "messaging/compatibility/v1/event-with-creation.json",
                        KafkaMessageMapper.STRUCTURED_CLOUD_EVENT,
                        MessageKind.EVENT,
                        envelope -> envelope.putObject("tracestate"),
                        new TraceContextCarrier(TRACE_PARENT, null)),
                new NonStringCreationMutation(
                        "command array state",
                        "messaging/compatibility/v1/command-with-creation.json",
                        KafkaMessageMapper.ASYNC_COMMAND,
                        MessageKind.ASYNC_COMMAND,
                        envelope -> envelope.putArray("tracestate"),
                        new TraceContextCarrier(TRACE_PARENT, null)),
                new NonStringCreationMutation(
                        "event numeric state",
                        "messaging/compatibility/v1/event-with-creation.json",
                        KafkaMessageMapper.STRUCTURED_CLOUD_EVENT,
                        MessageKind.EVENT,
                        envelope -> envelope.put("tracestate", 42),
                        new TraceContextCarrier(TRACE_PARENT, null)),
                new NonStringCreationMutation(
                        "command boolean state",
                        "messaging/compatibility/v1/command-with-creation.json",
                        KafkaMessageMapper.ASYNC_COMMAND,
                        MessageKind.ASYNC_COMMAND,
                        envelope -> envelope.put("tracestate", true),
                        new TraceContextCarrier(TRACE_PARENT, null)));
    }

    private static byte[] resourceBytes(String resource) throws IOException {
        try (var input = KafkaMessageMapperTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException("missing test resource: " + resource);
            }
            return input.readAllBytes();
        }
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

    private record CompatibilityFixture(
            String resource,
            String contentType,
            MessageKind kind,
            String messageType,
            TraceContextCarrier creationContext) {}

    private record CreationMutation(
            Consumer<tools.jackson.databind.node.ObjectNode> apply,
            TraceContextCarrier expected) {}

    private record NonStringCreationMutation(
            String description,
            String resource,
            String contentType,
            MessageKind kind,
            Consumer<tools.jackson.databind.node.ObjectNode> apply,
            TraceContextCarrier expected) {
        @Override
        public String toString() {
            return description;
        }
    }
}
