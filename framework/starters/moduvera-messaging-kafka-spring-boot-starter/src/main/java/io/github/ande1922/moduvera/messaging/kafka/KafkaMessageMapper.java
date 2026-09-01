package io.github.ande1922.moduvera.messaging.kafka;

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
import java.net.URI;
import java.time.Instant;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public final class KafkaMessageMapper {

    public static final String STRUCTURED_CLOUD_EVENT = "application/cloudevents+json";
    public static final String ASYNC_COMMAND = "application/vnd.moduvera.async-command+json";

    private static final String CLOUD_EVENTS_VERSION = "1.0";

    private final ObjectMapper json;

    public KafkaMessageMapper() {
        this(new ObjectMapper());
    }

    public KafkaMessageMapper(ObjectMapper json) {
        this.json = json;
    }

    public Message<byte[]> toSpringMessage(SerializedMessage message) {
        String wireContentType = wireContentType(message);
        return MessageBuilder.withPayload(encode(message))
                .setHeader(MessageHeaders.CONTENT_TYPE, wireContentType)
                .setHeader(KafkaHeaders.KEY, message.descriptor().partitionKey())
                .build();
    }

    public SerializedMessage fromSpringMessage(Message<byte[]> message) {
        String contentType = contentType(message.getHeaders());
        if (contentType.contains(STRUCTURED_CLOUD_EVENT)) {
            return decodeCloudEvent(message.getPayload());
        }
        if (contentType.contains(ASYNC_COMMAND)) {
            return decodeAsyncCommand(message.getPayload());
        }
        ObjectNode envelope = objectEnvelope(message.getPayload());
        if (envelope.has("specversion")) {
            return decodeCloudEvent(envelope);
        }
        if (envelope.has("kind")) {
            return decodeAsyncCommand(envelope);
        }
        throw new IllegalArgumentException("unsupported platform message content type: " + contentType);
    }

    public String wireContentType(SerializedMessage message) {
        return message.descriptor().kind() == MessageKind.EVENT
                ? STRUCTURED_CLOUD_EVENT
                : ASYNC_COMMAND;
    }

    private byte[] encode(SerializedMessage message) {
        try {
            return json.writeValueAsBytes(message.descriptor().kind() == MessageKind.EVENT
                    ? cloudEvent(message)
                    : asyncCommand(message));
        } catch (JacksonException invalid) {
            throw new IllegalArgumentException("message payload must be valid JSON", invalid);
        }
    }

    private ObjectNode cloudEvent(SerializedMessage message) throws JacksonException {
        MessageDescriptor descriptor = message.descriptor();
        ObjectNode envelope = commonEnvelope(descriptor);
        envelope.put("specversion", CLOUD_EVENTS_VERSION);
        envelope.put("datacontenttype", message.contentType());
        envelope.put("dataschema", schemaUrn(descriptor));
        envelope.set("data", json.readTree(message.payload()));
        return envelope;
    }

    private ObjectNode asyncCommand(SerializedMessage message) throws JacksonException {
        ObjectNode envelope = commonEnvelope(message.descriptor());
        envelope.put("kind", "async-command");
        envelope.put("contenttype", message.contentType());
        envelope.set("payload", json.readTree(message.payload()));
        return envelope;
    }

    private ObjectNode commonEnvelope(MessageDescriptor descriptor) {
        ObjectNode envelope = json.createObjectNode();
        envelope.put("id", descriptor.id().value());
        envelope.put("source", descriptor.source().toString());
        envelope.put("type", descriptor.type().value());
        envelope.put("destination", descriptor.destination().value());
        envelope.put("time", descriptor.time().toString());
        envelope.put("tenantid", descriptor.tenantId().value());
        envelope.put("actortype", descriptor.actor().type().name());
        envelope.put("actorsubject", descriptor.actor().subjectId());
        envelope.put("correlationid", descriptor.correlationId());
        if (descriptor.causationId() != null) {
            envelope.put("causationid", descriptor.causationId().value());
        }
        envelope.put("initiatortype", descriptor.initiator().type().name());
        envelope.put("initiatorsubject", descriptor.initiator().subjectId());
        envelope.put("partitionkey", descriptor.partitionKey());
        return envelope;
    }

    private SerializedMessage decodeCloudEvent(byte[] payload) {
        return decodeCloudEvent(objectEnvelope(payload));
    }

    private SerializedMessage decodeCloudEvent(ObjectNode envelope) {
        if (!CLOUD_EVENTS_VERSION.equals(requiredText(envelope, "specversion"))) {
            throw new IllegalArgumentException("unsupported CloudEvents specversion");
        }
        return decoded(
                envelope,
                MessageKind.EVENT,
                requiredText(envelope, "datacontenttype"),
                requiredNode(envelope, "data"));
    }

    private SerializedMessage decodeAsyncCommand(byte[] payload) {
        return decodeAsyncCommand(objectEnvelope(payload));
    }

    private SerializedMessage decodeAsyncCommand(ObjectNode envelope) {
        if (!"async-command".equals(requiredText(envelope, "kind"))) {
            throw new IllegalArgumentException("invalid asynchronous command envelope");
        }
        return decoded(
                envelope,
                MessageKind.ASYNC_COMMAND,
                requiredText(envelope, "contenttype"),
                requiredNode(envelope, "payload"));
    }

    private SerializedMessage decoded(
            ObjectNode envelope, MessageKind kind, String contentType, JsonNode businessPayload) {
        try {
            String causationId = optionalText(envelope, "causationid");
            MessageDescriptor descriptor = new MessageDescriptor(
                    new MessageId(requiredText(envelope, "id")),
                    kind,
                    new MessageType(requiredText(envelope, "type")),
                    URI.create(requiredText(envelope, "source")),
                    new Destination(requiredText(envelope, "destination")),
                    Instant.parse(requiredText(envelope, "time")),
                    new TenantId(requiredText(envelope, "tenantid")),
                    new Actor(
                            ActorType.valueOf(requiredText(envelope, "actortype")),
                            requiredText(envelope, "actorsubject")),
                    requiredText(envelope, "correlationid"),
                    causationId == null ? null : new MessageId(causationId),
                    new Initiator(
                            ActorType.valueOf(requiredText(envelope, "initiatortype")),
                            requiredText(envelope, "initiatorsubject")),
                    requiredText(envelope, "partitionkey"));
            return new SerializedMessage(descriptor, contentType, json.writeValueAsBytes(businessPayload));
        } catch (JacksonException invalid) {
            throw new IllegalArgumentException("cannot decode platform message payload", invalid);
        }
    }

    private ObjectNode objectEnvelope(byte[] payload) {
        try {
            JsonNode tree = json.readTree(payload);
            if (tree == null || !tree.isObject()) {
                throw new IllegalArgumentException("platform message envelope must be a JSON object");
            }
            return tree.asObject();
        } catch (JacksonException invalid) {
            throw new IllegalArgumentException("platform message envelope must be valid JSON", invalid);
        }
    }

    private static String contentType(MessageHeaders headers) {
        Object contentType = headers.get(MessageHeaders.CONTENT_TYPE);
        if (contentType == null) {
            throw new IllegalArgumentException("missing message content type");
        }
        return contentType.toString();
    }

    private static String requiredText(ObjectNode envelope, String name) {
        String value = optionalText(envelope, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing message attribute: " + name);
        }
        return value;
    }

    private static String optionalText(ObjectNode envelope, String name) {
        JsonNode value = envelope.get(name);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static JsonNode requiredNode(ObjectNode envelope, String name) {
        JsonNode value = envelope.get(name);
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw new IllegalArgumentException("missing message attribute: " + name);
        }
        return value;
    }

    private static String schemaUrn(MessageDescriptor descriptor) {
        return "urn:moduvera:schema:message:" + descriptor.type().value();
    }
}
