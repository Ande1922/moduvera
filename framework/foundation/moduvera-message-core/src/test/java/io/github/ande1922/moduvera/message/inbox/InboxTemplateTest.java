package io.github.ande1922.moduvera.message.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.inbox.memory.InMemoryInboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class InboxTemplateTest {

    @Test
    void appliesOneBusinessChangeForRepeatedDeliveryWithinATenant() {
        var template = new InboxTemplate(
                "inventory-reservation",
                new InMemoryInboxRepository(),
                new DirectTransactionBoundary(),
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
        AtomicInteger changes = new AtomicInteger();
        MessageId id = new MessageId("msg-1");

        InboxOutcome first = ExecutionContextHolder.call(context(), () -> template.handle(id, changes::incrementAndGet));
        InboxOutcome duplicate =
                ExecutionContextHolder.call(context(), () -> template.handle(id, changes::incrementAndGet));

        assertThat(first).isEqualTo(InboxOutcome.APPLIED);
        assertThat(duplicate).isEqualTo(InboxOutcome.DUPLICATE);
        assertThat(changes).hasValue(1);
    }

    @Test
    void letsIndependentConsumersHandleTheSameMessage() {
        var repository = new InMemoryInboxRepository();
        var transactions = new DirectTransactionBoundary();
        var clock = Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC);
        var inventory = new InboxTemplate("inventory-reservation", repository, transactions, clock);
        var audit = new InboxTemplate("inventory-audit", repository, transactions, clock);
        var changes = new AtomicInteger();
        var id = new MessageId("msg-shared");

        ExecutionContextHolder.run(context(), () -> inventory.handle(id, changes::incrementAndGet));
        ExecutionContextHolder.run(context(), () -> audit.handle(id, changes::incrementAndGet));

        assertThat(changes).hasValue(2);
    }

    private static ExecutionContext context() {
        Actor actor = new Actor(ActorType.SERVICE, "inventory-service");
        return ExecutionContext.initiatedBy(new TenantId("tenant-a"), actor, "corr-inbox");
    }

    private static final class DirectTransactionBoundary implements TransactionBoundary {

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }
}
