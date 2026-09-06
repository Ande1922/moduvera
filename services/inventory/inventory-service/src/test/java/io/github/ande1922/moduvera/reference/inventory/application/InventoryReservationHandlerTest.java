package io.github.ande1922.moduvera.reference.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.authorization.PermissionDeniedException;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import io.github.ande1922.moduvera.reference.inventory.domain.AllOrNothingReservationPolicy;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationDecision;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationExecution;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class InventoryReservationHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final MessageId MESSAGE_ID = new MessageId("message-42");
    private static final ReserveInventoryCommand COMMAND = new ReserveInventoryCommand(
            "reserve-order-42", 42, List.of(new ReserveInventoryLine(7, 2)));

    @Test
    void committedDeliverySkipsAuthorizationAndBusinessPreparation() {
        var repository = new RecordingInboxRepository(true);
        var store = new ScriptedStore(execution(true));
        var publisher = new RecordingPublisher();
        var handler = handler(repository, store, publisher);

        ExecutionContextHolder.run(context(Set.of()), () -> handler.handle(COMMAND, MESSAGE_ID));

        assertThat(repository.starts).isZero();
        assertThat(store.calls).isZero();
        assertThat(publisher.published).isEmpty();
    }

    @Test
    void authorizationRunsBeforeTheTransactionalBusinessWork() {
        var repository = new RecordingInboxRepository(false);
        var store = new ScriptedStore(execution(true));
        var handler = handler(repository, store, new RecordingPublisher());

        assertThatThrownBy(() -> ExecutionContextHolder.run(
                        context(Set.of()), () -> handler.handle(COMMAND, MESSAGE_ID)))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining(InventoryReservationHandler.RESERVE.value());
        assertThat(repository.starts).isZero();
        assertThat(store.calls).isZero();
    }

    @Test
    void newReservationAndRejectionPublishInsideTheInboxTransaction() {
        var repository = new RecordingInboxRepository(false);
        var reservedTransaction = new RecordingTransactionBoundary();
        var reservedPublisher = new RecordingPublisher(reservedTransaction);
        var reserved = handler(
                repository,
                new ScriptedStore(execution(true), reservedTransaction),
                reservedPublisher,
                reservedTransaction);

        ExecutionContextHolder.run(allowedContext(), () -> reserved.handle(COMMAND, MESSAGE_ID));

        assertThat(repository.starts).isOne();
        assertThat(reservedTransaction.calls).isOne();
        assertThat(reservedTransaction.active).isFalse();
        assertThat(reservedPublisher.published)
                .containsExactly(new InventoryReserved(COMMAND.commandId(), COMMAND.orderId(), NOW));

        var rejectedTransaction = new RecordingTransactionBoundary();
        var rejectedPublisher = new RecordingPublisher(rejectedTransaction);
        var rejectedExecution = new ReservationExecution(
                COMMAND.commandId(), COMMAND.orderId(), ReservationDecision.rejected(List.of(7L)), NOW, true);
        var rejected = handler(
                new RecordingInboxRepository(false),
                new ScriptedStore(rejectedExecution, rejectedTransaction),
                rejectedPublisher,
                rejectedTransaction);
        ExecutionContextHolder.run(allowedContext(), () -> rejected.handle(COMMAND, new MessageId("message-43")));
        assertThat(rejectedPublisher.published)
                .containsExactly(new InventoryRejected(COMMAND.commandId(), COMMAND.orderId(), List.of(7L), NOW));
    }

    @Test
    void existingBusinessResultCreatesTheNewInboxRecordWithoutAnotherOutboxIntent() {
        var repository = new RecordingInboxRepository(false);
        var publisher = new RecordingPublisher();
        var execution = execution(false);
        var handler = handler(repository, new ScriptedStore(execution), publisher);

        ExecutionContextHolder.run(allowedContext(), () -> handler.handle(COMMAND, MESSAGE_ID));

        assertThat(repository.starts).isOne();
        assertThat(publisher.published).isEmpty();
    }

    private static InventoryReservationHandler handler(
            InboxRepository repository, InventoryStore store, RecordingPublisher publisher) {
        return handler(repository, store, publisher, new RecordingTransactionBoundary());
    }

    private static InventoryReservationHandler handler(
            InboxRepository repository,
            InventoryStore store,
            RecordingPublisher publisher,
            TransactionBoundary transactions) {
        return new InventoryReservationHandler(
                new InboxTemplate("inventory-reservation", repository, transactions, CLOCK),
                store,
                new UseCaseAuthorizer(),
                CLOCK,
                publisher,
                new AllOrNothingReservationPolicy());
    }

    private static ReservationExecution execution(boolean created) {
        return new ReservationExecution(
                COMMAND.commandId(), COMMAND.orderId(), ReservationDecision.reserved(), NOW, created);
    }

    private static ExecutionContext allowedContext() {
        return context(Set.of(InventoryReservationHandler.RESERVE.value()));
    }

    private static ExecutionContext context(Set<String> permissions) {
        return ExecutionContext.initiatedBy(
                new TenantId("tenant-a"), new Actor(ActorType.SERVICE, "order-service", permissions), "corr");
    }

    private static final class RecordingInboxRepository implements InboxRepository {
        private final boolean processed;
        private int starts;

        private RecordingInboxRepository(boolean processed) {
            this.processed = processed;
        }

        @Override
        public boolean isProcessed(TenantId tenantId, String consumerId, MessageId messageId) {
            return processed;
        }

        @Override
        public boolean tryStart(TenantId tenantId, String consumerId, MessageId messageId, Instant processedAt) {
            starts++;
            return true;
        }
    }

    private static final class ScriptedStore implements InventoryStore {
        private final ReservationExecution result;
        private final RecordingTransactionBoundary transaction;
        private int calls;

        private ScriptedStore(ReservationExecution result) {
            this(result, null);
        }

        private ScriptedStore(
                ReservationExecution result, RecordingTransactionBoundary transaction) {
            this.result = result;
            this.transaction = transaction;
        }

        @Override
        public ReservationExecution reserve(
                ReserveInventoryCommand request,
                Instant now,
                io.github.ande1922.moduvera.reference.inventory.domain.ReservationPolicy policy) {
            if (transaction != null) {
                assertThat(transaction.active).isTrue();
            }
            calls++;
            return result;
        }
    }

    private static final class RecordingPublisher implements InventoryResultPublisher {
        private final List<Object> published = new ArrayList<>();
        private final RecordingTransactionBoundary transaction;

        private RecordingPublisher() {
            this(null);
        }

        private RecordingPublisher(RecordingTransactionBoundary transaction) {
            this.transaction = transaction;
        }

        @Override
        public void publish(io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult result) {
            if (transaction != null) {
                assertThat(transaction.active).isTrue();
            }
            published.add(result);
        }
    }

    private static final class RecordingTransactionBoundary implements TransactionBoundary {
        private boolean active;
        private int calls;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            calls++;
            active = true;
            try {
                return work.get();
            } finally {
                active = false;
            }
        }
    }
}
