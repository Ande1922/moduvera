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
import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import io.github.ande1922.moduvera.reference.inventory.domain.AllOrNothingReservationPolicy;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationDecision;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationExecution;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InventoryApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ReserveInventoryCommand COMMAND = new ReserveInventoryCommand(
            "reserve-order-42", 42, List.of(new ReserveInventoryLine(7, 2)));
    private static final ReservationPolicy POLICY = new AllOrNothingReservationPolicy();

    @Test
    void authorizationRejectsTheReservationBeforeStoreOrPublication() {
        var store = new ScriptedStore(new ReservationExecution(
                COMMAND.commandId(),
                COMMAND.orderId(),
                ReservationDecision.reserved(),
                NOW,
                true));
        var publisher = new RecordingPublisher();
        var service = service(store, publisher);

        assertThatThrownBy(() -> ExecutionContextHolder.call(
                        context(Set.of()), () -> service.reserve(COMMAND)))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining(InventoryApplicationService.RESERVE.value());
        assertThat(store.calls()).isZero();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    void newlyCreatedReservationReturnsAndPublishesTheScriptedResult() {
        var result = new InventoryReserved(COMMAND.commandId(), COMMAND.orderId(), NOW);
        var store = new ScriptedStore(new ReservationExecution(
                COMMAND.commandId(),
                COMMAND.orderId(),
                ReservationDecision.reserved(),
                NOW,
                true));
        var publisher = new RecordingPublisher();
        var service = service(store, publisher);

        var returned = ExecutionContextHolder.call(
                context(Set.of(InventoryApplicationService.RESERVE.value())),
                () -> service.reserve(COMMAND));

        assertThat(returned).isEqualTo(result);
        assertThat(store.calls()).isEqualTo(1);
        assertThat(store.request()).isSameAs(COMMAND);
        assertThat(store.policy()).isSameAs(POLICY);
        assertThat(publisher.published()).containsExactly(result);
    }

    @Test
    void newlyCreatedRejectionIsConvertedAndPublishedWithoutDatabaseTypes() {
        var store = new ScriptedStore(new ReservationExecution(
                COMMAND.commandId(),
                COMMAND.orderId(),
                ReservationDecision.rejected(List.of(7L)),
                NOW,
                true));
        var publisher = new RecordingPublisher();
        var service = service(store, publisher);

        var returned = ExecutionContextHolder.call(
                context(Set.of(InventoryApplicationService.RESERVE.value())),
                () -> service.reserve(COMMAND));

        assertThat(returned)
                .isEqualTo(new InventoryRejected(
                        COMMAND.commandId(), COMMAND.orderId(), List.of(7L), NOW));
        assertThat(publisher.published()).containsExactly(returned);
    }

    @Test
    void duplicateReservationReturnsTheScriptedResultWithoutPublishingAgain() {
        var result = new InventoryRejected(COMMAND.commandId(), 99, List.of(7L), NOW);
        var store = new ScriptedStore(new ReservationExecution(
                result.commandId(),
                result.orderId(),
                ReservationDecision.rejected(result.unavailableProductIds()),
                result.rejectedAt(),
                false));
        var publisher = new RecordingPublisher();
        var service = service(store, publisher);

        var returned = ExecutionContextHolder.call(
                context(Set.of(InventoryApplicationService.RESERVE.value())),
                () -> service.reserve(COMMAND));

        assertThat(returned).isEqualTo(result);
        assertThat(store.calls()).isEqualTo(1);
        assertThat(publisher.published()).isEmpty();
    }

    private static InventoryApplicationService service(
            InventoryStore store, InventoryResultPublisher publisher) {
        return new InventoryApplicationService(
                store, new UseCaseAuthorizer(), CLOCK, publisher, POLICY);
    }

    private static ExecutionContext context(Set<String> permissions) {
        Actor actor = new Actor(ActorType.SERVICE, "order-service", permissions);
        return ExecutionContext.initiatedBy(new TenantId("tenant-a"), actor, "corr-inventory");
    }

    private static final class ScriptedStore implements InventoryStore {

        private final ReservationExecution execution;
        private int calls;
        private ReserveInventoryCommand request;
        private ReservationPolicy policy;

        private ScriptedStore(ReservationExecution execution) {
            this.execution = execution;
        }

        @Override
        public ReservationExecution reserve(
                ReserveInventoryCommand request, Instant now, ReservationPolicy policy) {
            calls++;
            this.request = request;
            this.policy = policy;
            return execution;
        }

        private int calls() {
            return calls;
        }

        private ReserveInventoryCommand request() {
            return request;
        }

        private ReservationPolicy policy() {
            return policy;
        }
    }

    private static final class RecordingPublisher implements InventoryResultPublisher {

        private final List<InventoryReservationResult> published = new ArrayList<>();

        @Override
        public void publish(InventoryReservationResult result) {
            published.add(result);
        }

        private List<InventoryReservationResult> published() {
            return List.copyOf(published);
        }
    }
}
