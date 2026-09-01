package io.github.ande1922.moduvera.testing.messaging;

import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.SerializedMessage;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public record InboundMessageContractProbe(
        InboundMessageContract expectedContract,
        SerializedMessage validMessage,
        Consumer<SerializedMessage> deliver,
        IntSupplier applicationInvocations,
        Supplier<ExecutionContext> observedApplicationContext,
        Runnable assertAcceptedApplicationState) {

    public InboundMessageContractProbe {
        Objects.requireNonNull(expectedContract, "expectedContract");
        Objects.requireNonNull(validMessage, "validMessage");
        Objects.requireNonNull(deliver, "deliver");
        Objects.requireNonNull(applicationInvocations, "applicationInvocations");
        Objects.requireNonNull(observedApplicationContext, "observedApplicationContext");
        Objects.requireNonNull(assertAcceptedApplicationState, "assertAcceptedApplicationState");
    }
}
