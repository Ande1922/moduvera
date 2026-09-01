package io.github.ande1922.moduvera.message.handler;

import io.github.ande1922.moduvera.message.SerializedMessage;

@FunctionalInterface
public interface InboundMessageHandler {

    void handle(SerializedMessage message);
}
