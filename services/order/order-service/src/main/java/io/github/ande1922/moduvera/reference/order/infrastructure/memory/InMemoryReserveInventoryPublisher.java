package io.github.ande1922.moduvera.reference.order.infrastructure.memory;

import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.order.application.ReserveInventoryPublisher;
import java.util.ArrayList;
import java.util.List;

public final class InMemoryReserveInventoryPublisher implements ReserveInventoryPublisher {

    private final List<ReserveInventoryCommand> published = new ArrayList<>();

    @Override
    public synchronized void publish(ReserveInventoryCommand command) {
        published.add(command);
    }

    public synchronized List<ReserveInventoryCommand> published() {
        return List.copyOf(published);
    }
}
