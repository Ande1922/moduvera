package io.github.ande1922.moduvera.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MessageContractTest {

    @Test
    void usesTheMessageTypeAsTheOnlyContractVersion() {
        var descriptor = new MessageDescriptor(
                new MessageId("msg-1"),
                MessageKind.EVENT,
                new MessageType("io.github.ande1922.moduvera.reference.inventory.reserved.v1"),
                URI.create("urn:service:inventory"),
                new Destination("inventory.results"),
                Instant.parse("2026-08-30T00:00:00Z"),
                new TenantId("tenant-a"),
                new Actor(ActorType.SERVICE, "inventory-service"),
                "corr-1",
                new MessageId("cmd-1"),
                new Initiator(ActorType.USER, "alice"),
                "tenant-a:order-42");

        assertThat(SerializedMessage.json(descriptor, "{}").contentType()).isEqualTo(SerializedMessage.JSON);
        assertThat(descriptor.actor().subjectId()).isEqualTo("inventory-service");
        assertThat(descriptor.type()).isEqualTo(new MessageType("io.github.ande1922.moduvera.reference.inventory.reserved.v1"));
        assertThat(descriptor.partitionKey()).isEqualTo("tenant-a:order-42");
        assertThat(descriptor.creationContext()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> new Destination("inventory.results-v1"));
    }

    @Test
    void keepsCreationTraceAsBoundedPurePropagationValues() {
        var carrier = new TraceContextCarrier(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "vendor=value");

        assertThat(carrier.traceParent())
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(carrier.traceState()).isEqualTo("vendor=value");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TraceContextCarrier("x".repeat(513), null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TraceContextCarrier("opaque", "x".repeat(513)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TraceContextCarrier(" ", null));
        assertThatNullPointerException().isThrownBy(() -> new TraceContextCarrier(null, null));
    }
}
