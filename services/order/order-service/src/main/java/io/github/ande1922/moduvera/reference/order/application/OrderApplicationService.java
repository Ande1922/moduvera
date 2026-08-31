package io.github.ande1922.moduvera.reference.order.application;

import io.github.ande1922.moduvera.authorization.PermissionCode;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.api.GetProductQuery;
import io.github.ande1922.moduvera.reference.catalog.api.ProductSnapshot;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.identifier.IdentifierGenerator;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import io.github.ande1922.moduvera.reference.order.api.CreateOrderCommand;
import io.github.ande1922.moduvera.reference.order.api.GetOrderQuery;
import io.github.ande1922.moduvera.reference.order.api.OrderApi;
import io.github.ande1922.moduvera.reference.order.api.OrderView;
import io.github.ande1922.moduvera.reference.order.domain.Order;
import io.github.ande1922.moduvera.reference.order.domain.OrderLine;
import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;
import java.time.Clock;
import java.util.Currency;
import java.util.List;

public final class OrderApplicationService implements OrderApi, InventoryResultHandler {

    public static final PermissionCode CREATE = new PermissionCode("order:create");
    public static final PermissionCode READ = new PermissionCode("order:read");
    public static final PermissionCode APPLY_INVENTORY_RESULT =
            new PermissionCode("order:apply-inventory-result");

    private final CatalogApi catalog;
    private final OrderRepository orders;
    private final ReserveInventoryPublisher publisher;
    private final IdentifierGenerator identifiers;
    private final TransactionBoundary transactions;
    private final UseCaseAuthorizer authorizer;
    private final Clock clock;

    public OrderApplicationService(
            CatalogApi catalog,
            OrderRepository orders,
            ReserveInventoryPublisher publisher,
            IdentifierGenerator identifiers,
            TransactionBoundary transactions,
            UseCaseAuthorizer authorizer,
            Clock clock) {
        this.catalog = catalog;
        this.orders = orders;
        this.publisher = publisher;
        this.identifiers = identifiers;
        this.transactions = transactions;
        this.authorizer = authorizer;
        this.clock = clock;
    }

    @Override
    public OrderView create(CreateOrderCommand command) {
        authorizer.require(CREATE);
        List<ProductSnapshot> products = command.lines().stream()
                .map(line -> catalog.getProduct(new GetProductQuery(line.productId())))
                .toList();
        Currency currency = requireOneCurrency(products);
        List<OrderLine> lines = command.lines().stream()
                .map(request -> {
                    ProductSnapshot product = products.stream()
                            .filter(candidate -> candidate.productId() == request.productId())
                            .findFirst()
                            .orElseThrow();
                    return new OrderLine(
                            product.productId(), product.name(), request.quantity(), product.unitPrice());
                })
                .toList();
        long orderId = identifiers.nextId();
        Order order = Order.place(orderId, lines, currency, clock.instant());
        ReserveInventoryCommand reserve = new ReserveInventoryCommand(
                "reserve-order-" + orderId,
                orderId,
                lines.stream()
                        .map(line -> new ReserveInventoryLine(line.productId(), line.quantity()))
                        .toList());

        transactions.inTransaction(() -> {
            orders.save(order);
            publisher.publish(reserve);
        });
        return order.toView();
    }

    @Override
    public OrderView get(GetOrderQuery query) {
        authorizer.require(READ);
        return requireOrder(query.orderId()).toView();
    }

    @Override
    public void handle(InventoryReservationResult result) {
        authorizer.require(APPLY_INVENTORY_RESULT);
        Order order = requireOrder(result.orderId());
        if (result instanceof InventoryReserved reserved) {
            order.apply(reserved);
        } else if (result instanceof InventoryRejected rejected) {
            order.apply(rejected);
        }
        orders.save(order);
    }

    private Order requireOrder(long orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private static Currency requireOneCurrency(List<ProductSnapshot> products) {
        Currency currency = products.getFirst().currency();
        if (products.stream().anyMatch(product -> !currency.equals(product.currency()))) {
            throw new IllegalArgumentException("all products in one order must use the same currency");
        }
        return currency;
    }
}
