package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.inbox.InboxOutcome;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ReliableMessageConsumerCompatibilityTest {

    @Test
    @SuppressWarnings("removal")
    void deprecatedConsumerForwardsToTheReliableEndpointBoundary() {
        var mapper = new KafkaMessageMapper();
        var factory = new ReliableMessageConsumerFactory(
                mapper,
                (tenantId, consumerId, messageId, processedAt) -> true,
                new DirectTransactionBoundary(),
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
        var handled = new AtomicInteger();
        var consumer = factory.forConsumer("compatibility", contract());

        var outcome = consumer.handle(
                mapper.toSpringMessage(message()), ignored -> handled.incrementAndGet());

        assertThat(outcome).isEqualTo(InboxOutcome.APPLIED);
        assertThat(handled).hasValue(1);
    }

    private static InboundMessageContract contract() {
        return new InboundMessageContract(
                MessageKind.EVENT,
                new MessageType("notes.created.v1"),
                URI.create("urn:service:notes"),
                new Destination("notes.events"),
                new Actor(ActorType.SERVICE, "notes-service"));
    }

    private static SerializedMessage message() {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId("msg-compatibility"),
                        MessageKind.EVENT,
                        new MessageType("notes.created.v1"),
                        URI.create("urn:service:notes"),
                        new Destination("notes.events"),
                        Instant.parse("2026-08-30T00:00:00Z"),
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "notes-service"),
                        "corr-compatibility",
                        null,
                        new Initiator(ActorType.USER, "alice"),
                        "tenant-a:note-1"),
                "{}");
    }

    private static final class DirectTransactionBoundary implements TransactionBoundary {

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }
}
