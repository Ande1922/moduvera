package io.github.ande1922.moduvera.reference.inventory.api.architecturefixture;

import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageType;

public record MessageCoreApiViolation(MessageType messageType, Destination destination) {}
