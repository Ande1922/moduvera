package io.github.ande1922.moduvera.reference.order.adapter.inbound.http;

import io.github.ande1922.moduvera.reference.order.api.CreateOrderCommand;
import io.github.ande1922.moduvera.reference.order.api.CreateOrderLine;
import io.github.ande1922.moduvera.reference.order.api.GetOrderQuery;
import io.github.ande1922.moduvera.reference.order.api.OrderApi;
import io.github.ande1922.moduvera.reference.order.api.OrderStatus;
import io.github.ande1922.moduvera.reference.order.api.OrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/orders")
public class OrderHttpController {

    private final OrderApi orders;

    public OrderHttpController(OrderApi orders) {
        this.orders = orders;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = toResponse(orders.create(request.toCommand()));
        return ResponseEntity.created(URI.create("/v1/orders/" + response.orderId()))
                .body(response);
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@PathVariable @Positive long orderId) {
        return toResponse(orders.get(new GetOrderQuery(orderId)));
    }

    private static OrderResponse toResponse(OrderView view) {
        return new OrderResponse(
                Long.toString(view.orderId()),
                view.status(),
                view.lines().stream()
                        .map(line -> new OrderLineResponse(
                                Long.toString(line.productId()),
                                line.productName(),
                                line.quantity(),
                                line.unitPrice()))
                        .toList(),
                view.total(),
                view.currency(),
                view.placedAt());
    }

    public record CreateOrderRequest(@NotEmpty List<@Valid CreateOrderLineRequest> lines) {

        public CreateOrderRequest {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        CreateOrderCommand toCommand() {
            return new CreateOrderCommand(lines.stream()
                    .map(line -> new CreateOrderLine(line.productId(), line.quantity()))
                    .toList());
        }
    }

    public record CreateOrderLineRequest(@Positive long productId, @Positive int quantity) {}

    public record OrderResponse(
            String orderId,
            OrderStatus status,
            List<OrderLineResponse> lines,
            BigDecimal total,
            Currency currency,
            Instant placedAt) {}

    public record OrderLineResponse(
            String productId, String productName, int quantity, BigDecimal unitPrice) {}
}
