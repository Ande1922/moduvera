package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.util.MimeType;

public final class StreamBridgeMessageTransport implements MessageTransport {

    private final StreamOperations streamBridge;
    private final KafkaMessageMapper mapper;
    private final KafkaBindingRouteRegistry routes;

    public StreamBridgeMessageTransport(
            StreamOperations streamBridge, KafkaMessageMapper mapper, KafkaBindingRouteRegistry routes) {
        this.streamBridge = streamBridge;
        this.mapper = mapper;
        this.routes = routes;
    }

    @Override
    public void send(SerializedMessage message) {
        boolean accepted = streamBridge.send(
                routes.bindingFor(message.descriptor().destination()),
                mapper.toSpringMessage(message),
                MimeType.valueOf(mapper.wireContentType(message)));
        if (!accepted) {
            throw new IllegalStateException(
                    "message destination did not accept the message: "
                            + message.descriptor().destination().value());
        }
    }
}
