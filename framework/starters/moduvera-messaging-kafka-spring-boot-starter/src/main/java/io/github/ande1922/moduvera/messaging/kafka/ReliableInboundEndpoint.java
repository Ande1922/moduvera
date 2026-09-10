package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import java.time.Duration;
import java.util.function.Consumer;
import org.springframework.messaging.Message;

final class ReliableInboundEndpoint implements Consumer<Message<byte[]>> {

    private final KafkaMessageMapper mapper;
    private final InboundMessageContract contract;
    private final Consumer<SerializedMessage> inboundAdapter;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    ReliableInboundEndpoint(
            KafkaMessageMapper mapper,
            InboundMessageContract contract,
            Consumer<SerializedMessage> inboundAdapter,
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff) {
        this.mapper = mapper;
        this.contract = contract;
        this.inboundAdapter = inboundAdapter;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    @Override
    public void accept(Message<byte[]> message) {
        handle(message);
    }

    private void handle(Message<byte[]> message) {
        SerializedMessage serialized;
        try {
            serialized = mapper.fromSpringMessage(message);
        } catch (RuntimeException invalidEnvelope) {
            throw new NonRetryableMessageException("invalid message envelope", invalidEnvelope);
        }
        var descriptor = serialized.descriptor();
        contract.validate(descriptor);
        var context = new ExecutionContext(
                descriptor.tenantId(),
                contract.executionActor(),
                descriptor.initiator(),
                descriptor.correlationId());
        Duration backoff = initialBackoff;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                executeAttempt(context, serialized, message.getPayload().length, attempt);
                return;
            } catch (NonRetryableMessageException terminal) {
                throw terminal;
            } catch (RuntimeException retryable) {
                pause(backoff, context);
                backoff = backoff.multipliedBy(2);
                if (backoff.compareTo(maxBackoff) > 0) {
                    backoff = maxBackoff;
                }
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }

    private void executeAttempt(
            ExecutionContext context, SerializedMessage serialized, int bodySize, int attempt) {
        try (var executionScope = ExecutionContextHolder.open(context);
                var observation = new InboundAttemptObservation(serialized, bodySize, attempt, maxAttempts)) {
            try {
                inboundAdapter.accept(serialized);
                observation.succeeded();
            } catch (NonRetryableMessageException terminal) {
                observation.failed(terminal, false);
                var owned = new NonRetryableMessageException(terminal.getMessage(), terminal);
                InboundFailureDiagnostics.retain(owned, observation);
                throw owned;
            } catch (RuntimeException retryable) {
                observation.failed(retryable, attempt < maxAttempts);
                if (attempt == maxAttempts) {
                    var exhausted = new NonRetryableMessageException(
                            "message handling exhausted " + maxAttempts + " attempts", retryable);
                    InboundFailureDiagnostics.retain(exhausted, observation);
                    throw exhausted;
                }
                throw retryable;
            } catch (Error failure) {
                observation.failed(failure, false);
                if (InboundDeadLetterDiagnostics.isDeliveryObserved()) {
                    InboundContainerFailureDiagnostics.retain(failure);
                }
                throw failure;
            }
        }
    }

    private static void pause(Duration backoff, ExecutionContext context) {
        if (backoff.isZero()) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            var failure = new NonRetryableMessageException("message retry interrupted", interrupted);
            try (var ignored = ExecutionContextHolder.open(context)) {
                InboundFailureDiagnostics.retain(failure);
            }
            throw failure;
        }
    }
}
