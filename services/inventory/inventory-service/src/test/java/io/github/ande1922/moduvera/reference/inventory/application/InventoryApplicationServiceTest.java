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
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationDecision;
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

    @Test
    void authorizationRejectsTheReservationBeforeStoreOrPublication() {
        var result = new InventoryReserved(COMMAND.commandId(), COMMAND.orderId(), NOW);
        var store = new ScriptedStore(new ReservationDecision(result, true));
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
        var store = new ScriptedStore(new ReservationDecision(result, true));
        var publisher = new RecordingPublisher();
        var service = service(store, publisher);

        var returned = ExecutionContextHolder.call(
                context(Set.of(InventoryApplicationService.RESERVE.value())),
                () -> service.reserve(COMMAND));

        assertThat(returned).isSameAs(result);
        assertThat(store.calls()).isEqualTo(1);
        assertThat(publisher.published()).containsExactly(result);
    }

    @Test
    void duplicateReservationReturnsTheScriptedResultWithoutPublishingAgain() {
        var result = new InventoryRejected(COMMAND.commandId(), COMMAND.orderId(), List.of(7L), NOW);
        var store = new ScriptedStore(new ReservationDecision(result, false));
        var publisher = new RecordingPublisher();
        var service = service(store, publisher);

        var returned = ExecutionContextHolder.call(
                context(Set.of(InventoryApplicationService.RESERVE.value())),
                () -> service.reserve(COMMAND));

        assertThat(returned).isSameAs(result);
        assertThat(store.calls()).isEqualTo(1);
        assertThat(publisher.published()).isEmpty();
    }

    private static InventoryApplicationService service(
            InventoryStore store, InventoryResultPublisher publisher) {
        return new InventoryApplicationService(store, new UseCaseAuthorizer(), CLOCK, publisher);
    }

    private static ExecutionContext context(Set<String> permissions) {
        Actor actor = new Actor(ActorType.SERVICE, "order-service", permissions);
        return ExecutionContext.initiatedBy(new TenantId("tenant-a"), actor, "corr-inventory");
    }

    private static final class ScriptedStore implements InventoryStore {

        private final ReservationDecision decision;
        private int calls;

        private ScriptedStore(ReservationDecision decision) {
            this.decision = decision;
        }

        @Override
        public ReservationDecision reserve(ReserveInventoryCommand command, Instant now) {
            calls++;
            return decision;
        }

        private int calls() {
            return calls;
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
