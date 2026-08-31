# Frozen Shared Interface for the S/U Pilot

Status: **Proposed benchmark contract**  
Contract version: `store-saas-pilot-1`

Historical boundary: this contract intentionally remains frozen for pilot reproducibility. Its explicit `TenantId` Repository parameters no longer represent the production target after ADR 0003's tenant-transparent business-interface refinement. A future pilot must use a new contract version rather than silently changing this one.

This file freezes the candidate-visible business and persistence seams. The neutral seed must provide compilable types equivalent to these signatures; S and U candidates may not edit them. Package names may be mechanically prefixed by the seed generator, but names, fields, value semantics and method shapes remain identical.

## Common rules and values

- Service commands and queries never contain Tenant, Actor, Audit, Correlation or current time.
- The Application obtains them from trusted `ExecutionContext`, `Clock` and ID-generator dependencies.
- Every update command contains `expectedVersion`; every mutable aggregate view contains `version`.
- `TenantId`, `StoreId`, `ProductId`, `OrderId` and `CommandId` are validated value types over the frozen DDL scalar. Tenant/Command/Message IDs are trimmed, non-empty and case-sensitive with no case normalization; the MySQL identity columns use binary collation.
- `Money` is `(BigDecimal amount, String currencyCode)`, scale `4`, with exact arithmetic and one currency per Order.
- `AuditMetadata` is immutable `(createdAt, createdBy, updatedAt, updatedBy)`. Persistence owns its initial and refreshed values.
- `PageRequest` is `(int offset, int limit)` with `offset >= 0` and `1 <= limit <= 100`; `PageResult<T>` contains ordered items and total count.
- Public errors use the stable codes in `PROTOCOL.md`. Persistence/ORM exceptions never cross a seam.

## Store

```java
interface StoreApi {
    StoreView create(CreateStoreCommand command);
    StoreView rename(RenameStoreCommand command);
    StoreView deactivate(DeactivateStoreCommand command);
    StoreView get(GetStoreQuery query);
}

record CreateStoreCommand(String code, String name, String timeZoneId) {}
record RenameStoreCommand(StoreId storeId, String name, long expectedVersion) {}
record DeactivateStoreCommand(StoreId storeId, long expectedVersion) {}
record GetStoreQuery(StoreId storeId) {}
record StoreView(StoreId storeId, String code, String name, String timeZoneId,
                 StoreStatus status, long version, AuditMetadata audit) {}

interface StoreRepository {
    Optional<Store> findById(TenantId tenantId, StoreId storeId);
    Store save(Store candidate);
}
```

`StoreStatus` is `ACTIVE | INACTIVE`. Code is canonical uppercase and immutable. T01 uses these exact signatures.

T04 is the only allowed public Store delta:

```java
interface StoreApi {
    // the four T01 methods remain
    StoreView changeOperatingTime(ChangeStoreOperatingTimeCommand command);
}

record CreateStoreCommand(String code, String name, String timeZoneId,
                          LocalTime businessDayCutoff) {
    CreateStoreCommand(String code, String name, String timeZoneId) {
        this(code, name, timeZoneId, LocalTime.of(4, 0));
    }
}
record ChangeStoreOperatingTimeCommand(StoreId storeId, String timeZoneId,
                                       LocalTime businessDayCutoff,
                                       long expectedVersion) {}
record StoreView(StoreId storeId, String code, String name, String timeZoneId,
                 LocalTime businessDayCutoff, StoreStatus status,
                 long version, AuditMetadata audit) {}

```

The three-argument create constructor is source-compatible and means cutoff `04:00:00`. Adding the method and two record components above is the complete allowed public Interface change; no alternate overload, defaulting policy or transport-only shape may be invented.

The added Domain value type is exactly `StoreOperatingTime(ZoneId zoneId, LocalTime businessDayCutoff)` and exposes `LocalDate businessDate(Instant instant)`. The method converts the supplied Instant through `zoneId`; times before the cutoff map to the previous local date and times at/after it map to that local date. It never constructs or resolves an ambiguous local date-time.

## Catalog

```java
interface CatalogApi {
    ProductView createProduct(CreateProductCommand command);
    ProductView renameProduct(RenameProductCommand command);
    ProductView changePrice(ChangeProductPriceCommand command);
    ProductView changeStatus(ChangeProductStatusCommand command);
    Map<ProductId, ProductSnapshot> getProducts(GetProductsQuery query);
}

record CreateProductCommand(String name, Money unitPrice) {}
record RenameProductCommand(ProductId productId, String name,
                            long expectedVersion) {}
record ChangeProductPriceCommand(ProductId productId, Money unitPrice,
                                 long expectedVersion) {}
record ChangeProductStatusCommand(ProductId productId, ProductStatus status,
                                  long expectedVersion) {}
record GetProductsQuery(Set<ProductId> productIds) {}
record ProductView(ProductId productId, String name, Money unitPrice,
                   ProductStatus status, long version, AuditMetadata audit) {}
record ProductSnapshot(ProductId productId, String name, Money unitPrice,
                       ProductStatus status) {}

interface ProductRepository {
    Optional<Product> findById(TenantId tenantId, ProductId productId);
    Product save(Product candidate);
}
```

`ProductStatus` is `ENABLED | DISABLED`. `getProducts` rejects missing/disabled products for Order creation using Catalog-owned safe errors and performs one batch call for the exact requested ID set.

## Order and inventory intent

```java
interface OrderApi {
    OrderView createOrder(CreateOrderCommand command);
    OrderView getOrder(GetOrderQuery query);
    PageResult<OrderSummary> searchOrders(SearchOrdersQuery query);
}

record CreateOrderCommand(StoreId storeId, List<CreateOrderLine> lines) {}
record CreateOrderLine(ProductId productId, long quantity) {}
record GetOrderQuery(OrderId orderId) {}
record SearchOrdersQuery(StoreId storeId, OrderStatus status,
                         Instant placedFromInclusive, Instant placedUntilExclusive,
                         PageRequest page) {}

record OrderLineView(int lineNo, ProductId productId, String productName,
                     Money unitPrice, long quantity, Money lineTotal) {}
record OrderView(OrderId orderId, StoreId storeId, OrderStatus status,
                 List<OrderLineView> lines, Money total, Instant placedAt,
                 long version, AuditMetadata audit) {}
record OrderSummary(OrderId orderId, StoreId storeId, OrderStatus status,
                    Money total, Instant placedAt, long version) {}

interface SalesOrderRepository {
    Optional<SalesOrder> findById(TenantId tenantId, OrderId orderId);
    SalesOrder save(SalesOrder candidate);
}

interface ReserveInventoryOutbox {
    void append(ReserveInventoryCommand command);
}
```

`OrderStatus` initially contains `PENDING_STOCK`. T02 create accepts exactly two lines with two distinct Product IDs, positive quantities and a single currency. Catalog resolution happens once through `getProducts`. `SalesOrder.save` and `ReserveInventoryOutbox.append` occur in the same local Work Unit. The Outbox Adapter creates envelope identity, Actor/Initiator/Correlation and time from trusted dependencies; those values are not method parameters.

## Inventory

```java
interface InventoryApi {
    InventoryStockView adjust(AdjustInventoryCommand command);
    InventoryReservationResult reserve(ReserveInventoryCommand command);
    Optional<InventoryStockView> getStock(GetInventoryStockQuery query);
}

record AdjustInventoryCommand(CommandId commandId, StoreId storeId,
                              ProductId productId, long quantityDelta,
                              long expectedVersion) {}
record ReserveInventoryCommand(CommandId commandId, OrderId orderId,
                               StoreId storeId,
                               List<ReserveInventoryLine> lines) {}
record ReserveInventoryLine(ProductId productId, long quantity) {}
record GetInventoryStockQuery(StoreId storeId, ProductId productId) {}
record InventoryStockView(StoreId storeId, ProductId productId,
                          long availableQuantity, long version,
                          AuditMetadata audit) {}

sealed interface InventoryReservationResult
        permits InventoryReserved, InventoryRejected {}
record InventoryReserved(CommandId commandId, OrderId orderId,
                         Instant completedAt) implements InventoryReservationResult {}
record InventoryRejected(CommandId commandId, OrderId orderId,
                         List<ProductId> unavailableProductIds,
                         Instant completedAt) implements InventoryReservationResult {}
record InventoryReservationExecution(InventoryReservationResult result,
                                     MessageId resultMessageId,
                                     boolean newlyCompleted) {}

interface InventoryStore {
    InventoryStockView adjust(TenantId tenantId, AdjustInventoryCommand command,
                              Instant now);
    InventoryReservationExecution reserve(TenantId tenantId,
                                          ReserveInventoryCommand command,
                                          MessageId proposedResultMessageId,
                                          Instant now);
    Optional<InventoryStockView> find(TenantId tenantId, StoreId storeId,
                                      ProductId productId);
}

interface InventoryResultOutbox {
    void append(MessageId messageId, InventoryReservationResult result);
}

interface TenantInbox {
    void once(MessageId messageId, Runnable businessChange);
}
```

`InventoryStore` is the aggregate persistence seam for T03; no additional stock CRUD Repository is allowed. It owns stock CAS, adjustment-command replay, reservation replay and outcome persistence. Applied and rejected adjustments are both persisted in `inventory_adjustment`; replay returns the same view or rethrows the same safe failure without re-evaluating later stock. Reusing a command ID with different immutable request fields returns `inventory.command-conflict`. A duplicate identical reservation returns the stored result/message ID with `newlyCompleted=false`, without stock-version or Outbox change. The Application appends the result Outbox only when `newlyCompleted=true`; the generated proposed message ID, Store result and Outbox append share one Work Unit.

For adjustment, an absent stock row accepts only `expectedVersion=0` with a positive delta and inserts version `0`. An existing row requires the exact stored version and a successful change returns version `expectedVersion + 1`. A stale version, negative resulting quantity or command-payload mismatch changes neither stock nor Audit. Reservation lines contain two distinct Product IDs and are locked/updated in ascending Product-ID order so both labels receive the same deadlock policy.

The message entry point invokes `TenantInbox.once` in the same Work Unit as `InventoryStore.reserve` and `InventoryResultOutbox.append`. A duplicate delivery skips the callback; direct duplicate-command replay through `InventoryApi.reserve` returns the persisted business result. Inbox and Outbox physical shapes are frozen by the DDL. Message Context restoration remains outside these interfaces.

## Query and mapping boundary

Query Repositories, where required by the seed, return only the exact `*View`/`*Summary` projections above. They are supplied identically to S and U and are not eligible unified Domain entities. Neither candidate may expose a Domain object, persistence Row/DO, MyBatis type or generated record through these interfaces.

S and U may differ only behind the Aggregate Repository/InventoryStore implementations as registered in `PROTOCOL.md`. The same transaction runner, Clock, ID generators, Context boundary, Service API source, DDL migrations, public tests and evaluator TCK are shared.

## Frozen service-owned errors

- Store: `store.not-found`, `store.code-conflict`, `store.version-conflict`, `store.state-conflict`, `store.persistence-failure`.
- Catalog: `catalog.product-not-found`, `catalog.product-disabled`, `catalog.version-conflict`, `catalog.price-invalid`.
- Order: `order.not-found`, `order.lines-invalid`, `order.currency-conflict`, `order.amount-overflow`, `order.persistence-failure`.
- Inventory: `inventory.stock-not-found`, `inventory.quantity-conflict`, `inventory.version-conflict`, `inventory.command-conflict`, `inventory.persistence-failure`.
- Shared boundary: `request.validation-failed`, `security.permission-denied`, `security.tenant-mismatch`.

Not-found (including cross-Tenant) maps to 404, conflicts to 409, validation to 400, permission denial to 403 and unclassified safe persistence failure to 500. `InventoryRejected` is a successful business result, not an exception.
