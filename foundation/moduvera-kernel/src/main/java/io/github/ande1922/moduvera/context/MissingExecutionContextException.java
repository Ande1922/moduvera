package io.github.ande1922.moduvera.context;

public final class MissingExecutionContextException extends IllegalStateException {

    public MissingExecutionContextException() {
        super("execution context is required at this boundary");
    }
}
