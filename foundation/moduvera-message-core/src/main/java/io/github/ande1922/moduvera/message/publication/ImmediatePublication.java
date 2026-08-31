package io.github.ande1922.moduvera.message.publication;

import io.github.ande1922.moduvera.message.SerializedMessage;

/**
 * Publishes once and waits for the configured broker acknowledgement.
 *
 * <p>This capability does not persist, retry, redrive, or coordinate with a database transaction.
 * A timeout can therefore leave the broker outcome unknown.
 */
@FunctionalInterface
public interface ImmediatePublication {

    void publish(SerializedMessage message);
}
