package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import java.time.Clock;
import java.time.Duration;

public final class ReliableMessageConsumerFactory {

    private final KafkaMessageMapper mapper;
    private final InboxRepository repository;
    private final TransactionBoundary transactions;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    public ReliableMessageConsumerFactory(
            KafkaMessageMapper mapper,
            InboxRepository repository,
            TransactionBoundary transactions,
            Clock clock) {
        this(
                mapper,
                repository,
                transactions,
                clock,
                1,
                Duration.ofMillis(100),
                Duration.ofSeconds(1));
    }

    public ReliableMessageConsumerFactory(
            KafkaMessageMapper mapper,
            InboxRepository repository,
            TransactionBoundary transactions,
            Clock clock,
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff) {
        this.mapper = mapper;
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
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

    public ReliableMessageConsumer forConsumer(String consumerId, InboundMessageContract contract) {
        return new ReliableMessageConsumer(
                mapper,
                new InboxTemplate(consumerId, repository, transactions, clock),
                contract,
                maxAttempts,
                initialBackoff,
                maxBackoff);
    }
}
