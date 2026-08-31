package io.github.ande1922.moduvera.message.publication;

public final class ImmediatePublicationInTransactionException extends IllegalStateException {

    public ImmediatePublicationInTransactionException() {
        super("ImmediatePublication cannot run inside an active database transaction");
    }
}
