package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.outbox.OutboxBacklog;
import io.github.ande1922.moduvera.message.outbox.PublicationObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MicrometerPublicationObserverTest {

    @Test
    void recordsTheRequiredSignalsWithOnlyBoundedContractLabels() {
        var registry = new SimpleMeterRegistry();
        var observer = new MicrometerPublicationObserver(registry);

        observer.claimed(2);
        observer.completed(
                message(), PublicationObserver.Result.PUBLISHED, Duration.ofMillis(25));
        observer.staleToken("published");
        observer.claimConflict();
        observer.cleanup(3);
        observer.backlog(new OutboxBacklog(4, Duration.ofSeconds(5), 6));

        assertThat(registry.get("moduvera.messaging.outbox.claimed").counter().count())
                .isEqualTo(2);
        assertThat(registry.get("moduvera.messaging.outbox.publish")
                        .tags(
                                "destination", "inventory.commands",
                                "type", "io.github.ande1922.moduvera.reference.inventory.reserve.v1",
                                "result", "published")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("moduvera.messaging.outbox.claim.conflicts").counter().count())
                .isEqualTo(1);
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getKey())
                .doesNotContainAnyElementsOf(
                        Set.of("tenant", "actor", "messageId", "claimToken", "exception"));
    }

    private static SerializedMessage message() {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId("msg-1"),
                        MessageKind.ASYNC_COMMAND,
                        new MessageType("io.github.ande1922.moduvera.reference.inventory.reserve.v1"),
                        URI.create("urn:service:order"),
                        new Destination("inventory.commands"),
                        Instant.parse("2026-08-30T00:00:00Z"),
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "order-service"),
                        "corr-1",
                        null,
                        new Initiator(ActorType.USER, "alice"),
                        "tenant-a:order-1"),
                "{}");
    }
}
