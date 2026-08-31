package com.gaopc.platform.app.order.architecturefixture;

import com.gaopc.platform.message.SerializedMessage;

public final class LeafAppMessageHandlerViolation {

    void handle(SerializedMessage message) {
        message.payload();
    }
}
