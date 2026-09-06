package io.github.ande1922.moduvera.message.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.MessageId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class InboxTemplateTest {

    @Test
    void reportsProcessedStateWithoutOpeningATransaction() {
        var repository = new RecordingInboxRepository(true, true);
        var transactions = new RecordingTransactionBoundary();
        var template = new InboxTemplate(
                "inventory-reservation",
                repository,
                transactions,
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
        MessageId id = new MessageId("msg-processed");

        boolean processed = ExecutionContextHolder.call(context(), () -> template.isProcessed(id));

        assertThat(processed).isTrue();
        assertThat(transactions.executions()).isZero();
        assertThat(repository.queries())
                .containsExactly(new InboxIdentity(
                        new TenantId("tenant-a"), "inventory-reservation", id));
        assertThat(repository.starts()).isEmpty();
    }

    @Test
    void propagatesProcessedQueryFailures() {
        InboxRepository repository = new InboxRepository() {
            @Override
            public boolean isProcessed(
                    TenantId tenantId, String consumerId, MessageId messageId) {
                throw new IllegalStateException("inbox unavailable");
            }

            @Override
            public boolean tryStart(
                    TenantId tenantId,
                    String consumerId,
                    MessageId messageId,
                    Instant processedAt) {
                return true;
            }
        };
        var template = new InboxTemplate(
                "inventory-reservation",
                repository,
                new RecordingTransactionBoundary(),
                Clock.systemUTC());

        assertThatThrownBy(() -> ExecutionContextHolder.call(
                        context(), () -> template.isProcessed(new MessageId("msg-failure"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("inbox unavailable");
    }

    @Test
    void appliesAnAcceptedChangeInsideOneExplicitTransactionWithTrustedTenant() {
        var repository = new RecordingInboxRepository(true, false);
        var transactions = new RecordingTransactionBoundary();
        var template = new InboxTemplate(
                "inventory-reservation",
                repository,
                transactions,
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
        AtomicInteger changes = new AtomicInteger();
        MessageId id = new MessageId("msg-1");

        InboxOutcome outcome =
                ExecutionContextHolder.call(context(), () -> template.handle(id, changes::incrementAndGet));

        assertThat(outcome).isEqualTo(InboxOutcome.APPLIED);
        assertThat(changes).hasValue(1);
        assertThat(transactions.executions()).isEqualTo(1);
        assertThat(repository.starts())
                .containsExactly(new InboxStart(
                        new TenantId("tenant-a"),
                        "inventory-reservation",
                        id,
                        Instant.parse("2026-08-30T00:00:00Z")));
    }

    @Test
    void skipsTheChangeWhenInboxAdmissionIsRejected() {
        InboxRepository repository = new RecordingInboxRepository(false, true);
        var transactions = new RecordingTransactionBoundary();
        var template = new InboxTemplate(
                "inventory-reservation",
                repository,
                transactions,
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
        var changes = new AtomicInteger();

        InboxOutcome outcome = ExecutionContextHolder.call(
                context(), () -> template.handle(new MessageId("msg-rejected"), changes::incrementAndGet));

        assertThat(outcome).isEqualTo(InboxOutcome.DUPLICATE);
        assertThat(changes).hasValue(0);
        assertThat(transactions.executions()).isEqualTo(1);
    }

    private static ExecutionContext context() {
        Actor actor = new Actor(ActorType.SERVICE, "inventory-service");
        return ExecutionContext.initiatedBy(new TenantId("tenant-a"), actor, "corr-inbox");
    }

    private static final class RecordingInboxRepository implements InboxRepository {

        private final boolean accepted;
        private final boolean processed;
        private final List<InboxIdentity> queries = new ArrayList<>();
        private final List<InboxStart> starts = new ArrayList<>();

        private RecordingInboxRepository(boolean accepted, boolean processed) {
            this.accepted = accepted;
            this.processed = processed;
        }

        @Override
        public boolean isProcessed(TenantId tenantId, String consumerId, MessageId messageId) {
            queries.add(new InboxIdentity(tenantId, consumerId, messageId));
            return processed;
        }

        @Override
        public boolean tryStart(
                TenantId tenantId,
                String consumerId,
                MessageId messageId,
                Instant processedAt) {
            starts.add(new InboxStart(tenantId, consumerId, messageId, processedAt));
            return accepted;
        }

        private List<InboxStart> starts() {
            return List.copyOf(starts);
        }

        private List<InboxIdentity> queries() {
            return List.copyOf(queries);
        }
    }

    private record InboxIdentity(TenantId tenantId, String consumerId, MessageId messageId) {}

    private record InboxStart(
            TenantId tenantId, String consumerId, MessageId messageId, Instant processedAt) {}

    private static final class RecordingTransactionBoundary implements TransactionBoundary {

        private int executions;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            executions++;
            return work.get();
        }

        private int executions() {
            return executions;
        }
    }
}
