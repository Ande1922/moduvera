package io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.architecturefixture;

import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.handler.EventMessageHandler;
import java.time.Duration;

public final class RetryingInventoryEventProcessor implements EventMessageHandler {

    @Override
    public void handle(SerializedMessage message) {
        try {
            Thread.sleep(Duration.ofMillis(1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new NonRetryableMessageException("retry interrupted", interrupted);
        }
    }
}
