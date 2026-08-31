package io.github.ande1922.moduvera.reference.order.domain;

import java.util.Optional;

public interface OrderRepository {

    Optional<Order> findById(long orderId);

    void save(Order order);
}
