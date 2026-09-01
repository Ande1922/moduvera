package io.github.ande1922.moduvera.message.publication;

import io.github.ande1922.moduvera.message.SerializedMessage;

/**
 * Enlists a publication intent in the caller's local database transaction.
 *
 * <p>Successful return does not mean that a broker acknowledged the message. It only means that
 * the intent was appended to the durable publication store and will commit or roll back with the
 * caller's transaction.
 */
@FunctionalInterface
public interface DurablePublication {

    void append(SerializedMessage message);
}
