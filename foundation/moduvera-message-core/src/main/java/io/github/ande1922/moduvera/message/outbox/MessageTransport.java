package io.github.ande1922.moduvera.message.outbox;

import io.github.ande1922.moduvera.message.SerializedMessage;

@FunctionalInterface
public interface MessageTransport {

    void send(SerializedMessage message);
}
