package io.github.ande1922.moduvera.message.handler;

import io.github.ande1922.moduvera.message.MessageId;

@FunctionalInterface
public interface ApplicationMessageHandler<P> {

    void handle(P payload, MessageId messageId);
}
