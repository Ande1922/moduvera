package io.github.ande1922.moduvera.reference.app.order.architecturefixture;

import io.github.ande1922.moduvera.message.SerializedMessage;

public final class LeafAppMessageHandlerViolation {

    void handle(SerializedMessage message) {
        message.payload();
    }
}
