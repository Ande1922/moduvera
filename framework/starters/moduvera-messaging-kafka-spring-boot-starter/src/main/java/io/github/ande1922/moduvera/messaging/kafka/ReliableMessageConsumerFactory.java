package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.SerializedMessage;
import java.time.Duration;
import java.util.function.Consumer;
import org.springframework.messaging.Message;

public final class ReliableMessageConsumerFactory {

    private final KafkaMessageMapper mapper;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    public ReliableMessageConsumerFactory(KafkaMessageMapper mapper) {
        this(
                mapper,
                1,
                Duration.ofMillis(100),
                Duration.ofSeconds(1));
    }

    public ReliableMessageConsumerFactory(
            KafkaMessageMapper mapper,
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff) {
        this.mapper = mapper;
        if (maxAttempts < 1
                || initialBackoff == null
                || initialBackoff.isNegative()
                || maxBackoff == null
                || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("consumer retry policy must be bounded and non-negative");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    public Consumer<Message<byte[]>> forContract(
            InboundMessageContract contract, Consumer<SerializedMessage> inboundAdapter) {
        return new ReliableInboundEndpoint(
                mapper,
                contract,
                inboundAdapter,
                maxAttempts,
                initialBackoff,
                maxBackoff);
    }
}
