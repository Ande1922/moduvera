package io.github.ande1922.moduvera.testing.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InboundMessageContractTckTest implements InboundMessageContractTck {

    private static final TenantId TENANT_ID = new TenantId("contract-tck-tenant");
    private static final Initiator INITIATOR = new Initiator(ActorType.USER, "contract-tck-user");
    private static final String CORRELATION_ID = "contract-tck-correlation";
    private static final Actor EXECUTION_ACTOR =
            new Actor(ActorType.SERVICE, "contract-tck-consumer", Set.of("contract-tck:consume"));
    private static final InboundMessageContract CONTRACT = new InboundMessageContract(
            MessageKind.ASYNC_COMMAND,
            new MessageType("contract-tck.sample.v1"),
            URI.create("urn:moduvera:contract-tck:producer"),
            new Destination("contract-tck.sample"),
            EXECUTION_ACTOR);
    private static final String PAYLOAD = "{\"sample\":\"accepted\"}";

    @Override
    public InboundMessageContractProbe newInboundMessageContractProbe() {
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<ExecutionContext> observedContext = new AtomicReference<>();
        AtomicReference<String> acceptedPayload = new AtomicReference<>();
        SerializedMessage validMessage = SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId("018f89f0-2e4b-7d00-8000-000000000001"),
                        CONTRACT.kind(),
                        CONTRACT.type(),
                        CONTRACT.source(),
                        CONTRACT.destination(),
                        Instant.parse("2026-09-01T00:00:00Z"),
                        TENANT_ID,
                        new Actor(ActorType.SERVICE, "wire-producer", Set.of("wire:permission")),
                        CORRELATION_ID,
                        null,
                        INITIATOR,
                        "contract-tck-partition"),
                PAYLOAD);

        return new InboundMessageContractProbe(
                CONTRACT,
                validMessage,
                message -> deliver(message, invocations, observedContext, acceptedPayload),
                invocations::get,
                observedContext::get,
                () -> assertEquals(PAYLOAD, acceptedPayload.get()));
    }

    @ParameterizedTest(name = "detects a consumer that ignores {0}")
    @EnumSource(IdentityDimension.class)
    void detectsAConsumerThatIgnoresOneContractIdentity(IdentityDimension ignored) {
        InboundMessageContractTck mutant = () -> newMutationProbe(ignored);

        AssertionError failure = assertThrows(
                AssertionError.class,
                mutant::rejectsEachContractIdentityMismatchBeforeApplicationInvocation);

        assertTrue(failure.getMessage().contains("mismatched " + ignored.label));
    }

    private InboundMessageContractProbe newMutationProbe(IdentityDimension ignored) {
        SerializedMessage validMessage = newInboundMessageContractProbe().validMessage();
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<ExecutionContext> observedContext = new AtomicReference<>();
        return new InboundMessageContractProbe(
                CONTRACT,
                validMessage,
                message -> selectivelyDeliver(message, ignored, invocations, observedContext),
                invocations::get,
                observedContext::get,
                () -> {});
    }

    private static void selectivelyDeliver(
            SerializedMessage message,
            IdentityDimension ignored,
            AtomicInteger invocations,
            AtomicReference<ExecutionContext> observedContext) {
        MessageDescriptor descriptor = message.descriptor();
        boolean matches = (ignored == IdentityDimension.KIND || CONTRACT.kind() == descriptor.kind())
                && (ignored == IdentityDimension.TYPE || CONTRACT.type().equals(descriptor.type()))
                && (ignored == IdentityDimension.SOURCE || CONTRACT.source().equals(descriptor.source()))
                && (ignored == IdentityDimension.DESTINATION
                        || CONTRACT.destination().equals(descriptor.destination()));
        if (!matches) {
            throw new NonRetryableMessageException("message does not match the expected inbound contract");
        }
        ExecutionContext context = new ExecutionContext(
                descriptor.tenantId(), EXECUTION_ACTOR, descriptor.initiator(), descriptor.correlationId());
        ExecutionContextHolder.run(context, () -> {
            observedContext.set(ExecutionContextHolder.require());
            invocations.incrementAndGet();
        });
    }

    private static void deliver(
            SerializedMessage message,
            AtomicInteger invocations,
            AtomicReference<ExecutionContext> observedContext,
            AtomicReference<String> acceptedPayload) {
        MessageDescriptor descriptor = message.descriptor();
        CONTRACT.validate(descriptor);
        ExecutionContext context =
                new ExecutionContext(descriptor.tenantId(), EXECUTION_ACTOR, descriptor.initiator(), descriptor.correlationId());
        ExecutionContextHolder.run(context, () -> {
            observedContext.set(ExecutionContextHolder.require());
            acceptedPayload.set(new String(message.payload(), StandardCharsets.UTF_8));
            invocations.incrementAndGet();
        });
    }

    private enum IdentityDimension {
        KIND("kind"),
        TYPE("type"),
        SOURCE("source"),
        DESTINATION("destination");

        private final String label;

        IdentityDimension(String label) {
            this.label = label;
        }
    }
}
