package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.inbox.InboxOutcome;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import java.util.function.Consumer;
import java.time.Duration;
import org.springframework.messaging.Message;

/** @deprecated Bind one handler through {@link ReliableMessageConsumerFactory} instead. */
@Deprecated(forRemoval = true)
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
        return new ReliableInboundEndpoint(
                        mapper,
                        inbox,
                        contract,
                        businessHandler::accept,
                        maxAttempts,
                        initialBackoff,
                        maxBackoff)
                .handle(message);
    }
}
