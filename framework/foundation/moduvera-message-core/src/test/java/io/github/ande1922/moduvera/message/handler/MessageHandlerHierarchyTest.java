package io.github.ande1922.moduvera.message.handler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.message.MessageId;
import org.junit.jupiter.api.Test;

class MessageHandlerHierarchyTest {

    @Test
    void applicationHandlersReceiveTypedPayloadAndOriginalMessageId() {
        var handledPayload = new java.util.concurrent.atomic.AtomicReference<String>();
        var handledMessageId = new java.util.concurrent.atomic.AtomicReference<MessageId>();
        ApplicationMessageHandler<String> handler = (payload, messageId) -> {
            handledPayload.set(payload);
            handledMessageId.set(messageId);
        };
        var messageId = new MessageId("message-1");

        handler.handle("payload", messageId);

        assertThat(handledPayload).hasValue("payload");
        assertThat(handledMessageId).hasValue(messageId);
        assertThat(ApplicationMessageHandler.class).hasAnnotation(FunctionalInterface.class);
    }
}
