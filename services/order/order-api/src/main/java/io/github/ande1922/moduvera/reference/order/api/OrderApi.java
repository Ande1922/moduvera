package io.github.ande1922.moduvera.reference.order.api;

public interface OrderApi {

    OrderView create(CreateOrderCommand command);

    OrderView get(GetOrderQuery query);
}
