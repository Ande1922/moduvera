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
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MicrometerPublicationObserverTest {

    @Test
    void preservesAllNineMeterContractsAndObserverDurationSemantics() {
        var registry = new SimpleMeterRegistry();
        var observer = new MicrometerPublicationObserver(registry);

        observer.claimed(2);
        observer.completed(
                message(), PublicationObserver.Result.PUBLISHED, Duration.ofMillis(25));
        observer.completed(message(), PublicationObserver.Result.RETRY, Duration.ofMillis(40));
        observer.completed(message(), PublicationObserver.Result.TERMINAL, Duration.ofMillis(55));
        observer.staleToken("published");
        observer.staleToken("retry");
        observer.staleToken("terminal");
        observer.claimConflict();
        observer.cleanup(3);
        observer.backlog(new OutboxBacklog(4, Duration.ofSeconds(5), 6));

        assertThat(registry.getMeters())
                .extracting(meter -> meter.getId().getName())
                .containsOnly(
                        "moduvera.messaging.outbox.claimed",
                        "moduvera.messaging.outbox.publish",
                        "moduvera.messaging.outbox.broker.ack",
                        "moduvera.messaging.outbox.stale.token",
                        "moduvera.messaging.outbox.claim.conflicts",
                        "moduvera.messaging.outbox.cleanup.deleted",
                        "moduvera.messaging.outbox.pending",
                        "moduvera.messaging.outbox.pending.oldest.seconds",
                        "moduvera.messaging.outbox.terminal");
        assertMeterContract(
                registry, "moduvera.messaging.outbox.claimed", Meter.Type.COUNTER, null);
        assertMeterContract(
                registry, "moduvera.messaging.outbox.publish", Meter.Type.COUNTER, null);
        assertMeterContract(
                registry, "moduvera.messaging.outbox.broker.ack", Meter.Type.TIMER, "seconds");
        assertMeterContract(
                registry, "moduvera.messaging.outbox.stale.token", Meter.Type.COUNTER, null);
        assertMeterContract(
                registry,
                "moduvera.messaging.outbox.claim.conflicts",
                Meter.Type.COUNTER,
                null);
        assertMeterContract(
                registry,
                "moduvera.messaging.outbox.cleanup.deleted",
                Meter.Type.COUNTER,
                null);
        assertMeterContract(
                registry, "moduvera.messaging.outbox.pending", Meter.Type.GAUGE, null);
        assertMeterContract(
                registry,
                "moduvera.messaging.outbox.pending.oldest.seconds",
                Meter.Type.GAUGE,
                null);
        assertMeterContract(
                registry, "moduvera.messaging.outbox.terminal", Meter.Type.GAUGE, null);

        assertThat(registry.get("moduvera.messaging.outbox.claimed").counter().count())
                .isEqualTo(2);
        for (PublicationObserver.Result result : PublicationObserver.Result.values()) {
            String resultTag = result.name().toLowerCase(java.util.Locale.ROOT);
            assertThat(registry.get("moduvera.messaging.outbox.publish")
                            .tags(
                                    "destination", "inventory.commands",
                                    "type",
                                    "io.github.ande1922.moduvera.reference.inventory.reserve.v1",
                                    "result", resultTag)
                            .counter()
                            .count())
                    .isEqualTo(1);
        }
        assertThat(registry.get("moduvera.messaging.outbox.broker.ack")
                        .tag("result", "published")
                        .timer()
                        .totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(25);
        assertThat(registry.get("moduvera.messaging.outbox.broker.ack")
                        .tag("result", "retry")
                        .timer()
                        .totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(40);
        assertThat(registry.get("moduvera.messaging.outbox.broker.ack")
                        .tag("result", "terminal")
                        .timer()
                        .totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(55);
        assertThat(registry.get("moduvera.messaging.outbox.stale.token")
                        .tag("operation", "published")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("moduvera.messaging.outbox.stale.token")
                        .tag("operation", "retry")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("moduvera.messaging.outbox.stale.token")
                        .tag("operation", "terminal")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("moduvera.messaging.outbox.claim.conflicts").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("moduvera.messaging.outbox.cleanup.deleted").counter().count())
                .isEqualTo(3);
        assertThat(registry.get("moduvera.messaging.outbox.pending").gauge().value()).isEqualTo(4);
        assertThat(registry.get("moduvera.messaging.outbox.pending.oldest.seconds")
                        .gauge()
                        .value())
                .isEqualTo(5);
        assertThat(registry.get("moduvera.messaging.outbox.terminal").gauge().value()).isEqualTo(6);
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsOnly("destination", "type", "result", "operation");
    }

    private static void assertMeterContract(
            MeterRegistry registry, String name, Meter.Type type, String baseUnit) {
        assertThat(registry.find(name).meters())
                .isNotEmpty()
                .allSatisfy(meter -> {
                    assertThat(meter.getId().getType()).isEqualTo(type);
                    assertThat(meter.getId().getBaseUnit()).isEqualTo(baseUnit);
                });
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
