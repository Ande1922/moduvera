package io.github.ande1922.moduvera.message.outbox;

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
import io.github.ande1922.moduvera.message.outbox.memory.InMemoryDurablePublication;
import io.github.ande1922.moduvera.message.outbox.memory.InMemoryOutboxStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OutboxAdministrationTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    @Test
    void redrivesTerminalWithTheFencingTokenAndPreservesItsIdentity() {
        var store = new InMemoryOutboxStore(Clock.fixed(NOW, ZoneOffset.UTC));
        append(store, message("msg-terminal", "destination-a", "key-a"));
        var batch = store.claim(1, Duration.ofSeconds(30)).orElseThrow();
        store.markTerminal(
                new MessageId("msg-terminal"), batch.claimToken(), NOW, "InvalidPayload");

        var terminal = store.findTerminal(10).getFirst();
        assertThat(store.redrive(terminal.id(), "stale-token")).isFalse();
        assertThat(store.redrive(terminal.id(), terminal.redriveToken())).isTrue();

        var redriven = store.claim(1, Duration.ofSeconds(30)).orElseThrow();
        assertThat(redriven.messages().getFirst().message().descriptor().id())
                .isEqualTo(new MessageId("msg-terminal"));
        assertThat(redriven.messages().getFirst().failedAttempts()).isEqualTo(1);
    }

    @Test
    void cleanupIsBoundedAndNeverDeletesPendingOrTerminalRows() {
        var store = new InMemoryOutboxStore(Clock.fixed(NOW, ZoneOffset.UTC));
        append(store, message("msg-old-a", "destination-a", "key-a"));
        append(store, message("msg-old-b", "destination-a", "key-b"));
        append(store, message("msg-terminal", "destination-a", "key-c"));
        append(store, message("msg-pending", "destination-a", "key-d"));
        var batch = store.claim(4, Duration.ofSeconds(30)).orElseThrow();
        for (var entry : batch.messages()) {
            if (entry.message().descriptor().id().value().equals("msg-terminal")) {
                store.markTerminal(entry.message().descriptor().id(), batch.claimToken(), NOW, "Poison");
            } else if (!entry.message().descriptor().id().value().equals("msg-pending")) {
                store.markPublished(entry.message().descriptor().id(), batch.claimToken(), Instant.EPOCH);
            }
        }

        assertThat(store.deletePublishedBefore(NOW.minusSeconds(1), 1)).isEqualTo(1);
        assertThat(store.deletePublishedBefore(NOW.minusSeconds(1), 1)).isEqualTo(1);
        assertThat(store.deletePublishedBefore(NOW.minusSeconds(1), 1)).isZero();
        assertThat(store.findTerminal(10)).hasSize(1);
        assertThat(store.backlog().pendingCount()).isEqualTo(1);
        assertThat(store.backlog().terminalCount()).isEqualTo(1);
    }

    private static SerializedMessage message(String id, String destination, String key) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId(id),
                        MessageKind.EVENT,
                        new MessageType("io.github.ande1922.moduvera.test.event.v1"),
                        URI.create("urn:service:test"),
                        new Destination(destination),
                        NOW.minus(Duration.ofHours(1)),
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "test-service"),
                        "corr-1",
                        null,
                        new Initiator(ActorType.SYSTEM, "test"),
                        key),
                "{}");
    }

    private static void append(InMemoryOutboxStore store, SerializedMessage message) {
        new InMemoryDurablePublication(store).append(message);
    }
}
