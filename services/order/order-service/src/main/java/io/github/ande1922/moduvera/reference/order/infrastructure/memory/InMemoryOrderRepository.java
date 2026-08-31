package io.github.ande1922.moduvera.reference.order.infrastructure.memory;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.reference.order.domain.Order;
import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryOrderRepository implements OrderRepository {

    private final ConcurrentMap<Key, Order> orders = new ConcurrentHashMap<>();

    @Override
    public Optional<Order> findById(long orderId) {
        return Optional.ofNullable(orders.get(new Key(currentTenant(), orderId)));
    }

    @Override
    public void save(Order order) {
        orders.put(new Key(currentTenant(), order.id()), order);
    }

    private static TenantId currentTenant() {
        return ExecutionContextHolder.require().tenantId();
    }

    private record Key(TenantId tenantId, long orderId) {}
}
