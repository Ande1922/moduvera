package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.inbox.InboxOutcome;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import java.util.function.Consumer;
import java.time.Duration;
import org.springframework.messaging.Message;

public final class ReliableMessageConsumer {

    private final KafkaMessageMapper mapper;
    private final InboxTemplate inbox;
    private final InboundMessageContract contract;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    ReliableMessageConsumer(
            KafkaMessageMapper mapper,
            InboxTemplate inbox,
            InboundMessageContract contract,
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff) {
        this.mapper = mapper;
        this.inbox = inbox;
        this.contract = contract;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    public InboxOutcome handle(Message<byte[]> message, Consumer<SerializedMessage> businessHandler) {
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
                return ExecutionContextHolder.call(
                        context,
                        () -> inbox.handle(
                                descriptor.id(), () -> businessHandler.accept(serialized)));
            } catch (NonRetryableMessageException terminal) {
                throw terminal;
            } catch (RuntimeException retryable) {
                if (attempt == maxAttempts) {
                    throw new NonRetryableMessageException(
                            "message handling exhausted " + maxAttempts + " attempts", retryable);
                }
                pause(backoff);
                backoff = backoff.multipliedBy(2);
                if (backoff.compareTo(maxBackoff) > 0) {
                    backoff = maxBackoff;
                }
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }

    private static void pause(Duration backoff) {
        if (backoff.isZero()) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new NonRetryableMessageException("message retry interrupted", interrupted);
        }
    }
}
