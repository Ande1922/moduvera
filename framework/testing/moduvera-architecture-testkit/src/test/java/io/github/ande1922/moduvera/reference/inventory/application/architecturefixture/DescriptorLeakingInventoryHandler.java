package io.github.ande1922.moduvera.reference.inventory.application.architecturefixture;

import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.handler.ApplicationMessageHandler;

public final class DescriptorLeakingInventoryHandler
        implements ApplicationMessageHandler<MessageDescriptor> {

    @Override
    public void handle(MessageDescriptor descriptor, MessageId messageId) {
        descriptor.actor();
    }
}
