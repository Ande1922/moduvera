package io.github.ande1922.moduvera.reference.order.design;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.reference.order.api.OrderStatus;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Executable design probe for the entity/audit seam. These types are deliberately test-only: the
 * probe compares lifecycle costs without selecting a production model.
 */
class EntityAuditModelDemoTest {

    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final Instant CREATED_AT = Instant.parse("2026-08-30T01:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-30T02:00:00Z");

    @Test
    void adapterOwnedAuditKeepsTechnicalStateOutsideTheDomainObject() {
        MutableAuditSource auditSource = new MutableAuditSource(new AuditStamp(CREATED_AT, "user-1"));
        OutsideAuditRepository repository = new OutsideAuditRepository(auditSource);
        BusinessOnlyOrder order = BusinessOnlyOrder.place(TENANT, 101L);

        repository.save(order);
        AuditMetadata created = repository.auditOf(TENANT, 101L).orElseThrow();

        assertThat(created.createdAt()).isEqualTo(CREATED_AT);
        assertThat(created.updatedAt()).isEqualTo(CREATED_AT);

        order.confirm();
        auditSource.use(new AuditStamp(UPDATED_AT, "service-inventory"));
        repository.save(order);

        assertThat(repository.findById(TENANT, 101L).orElseThrow().status())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(repository.auditOf(TENANT, 101L).orElseThrow().updatedBy())
                .isEqualTo("service-inventory");
    }

    @Test
    void adapterOwnedImmutableAuditInsideDomainRequiresSaveToReturnTheRefreshedAggregate() {
        MutableAuditSource auditSource = new MutableAuditSource(new AuditStamp(CREATED_AT, "user-1"));
        ReturningAuditedRepository repository = new ReturningAuditedRepository(auditSource);
        PersistenceAuditedOrder draft = PersistenceAuditedOrder.place(TENANT, 102L);

        PersistenceAuditedOrder persisted = repository.save(draft);

        assertThat(draft.audit()).isEqualTo(AuditMetadata.unpersisted());
        assertThat(persisted.audit().createdAt()).isEqualTo(CREATED_AT);

        persisted.confirm();
        auditSource.use(new AuditStamp(UPDATED_AT, "service-inventory"));
        PersistenceAuditedOrder updated = repository.save(persisted);

        assertThat(updated.audit().createdAt()).isEqualTo(CREATED_AT);
        assertThat(updated.audit().updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(updated.audit().updatedBy()).isEqualTo("service-inventory");
    }

    @Test
    void applicationOwnedAuditKeepsTheObjectCurrentButAddsAuditToEveryMutationInterface() {
        AuditStamp creator = new AuditStamp(CREATED_AT, "user-1");
        ApplicationAuditedOrder order = ApplicationAuditedOrder.place(TENANT, 103L, creator);
        VoidAuditedRepository repository = new VoidAuditedRepository();

        repository.save(order);
        order.confirm(new AuditStamp(UPDATED_AT, "service-inventory"));
        repository.save(order);

        ApplicationAuditedOrder restored = repository.findById(TENANT, 103L).orElseThrow();
        assertThat(restored.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(restored.audit().createdBy()).isEqualTo("user-1");
        assertThat(restored.audit().updatedBy()).isEqualTo("service-inventory");
    }

    private abstract static class BaseEntity<ID> {

        private final ID id;

        protected BaseEntity(ID id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        protected final ID id() {
            return id;
        }
    }

    private abstract static class TenantEntity<ID> extends BaseEntity<ID> {

        private final TenantId tenantId;

        protected TenantEntity(ID id, TenantId tenantId) {
            super(id);
            this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        }

        protected final TenantId tenantId() {
            return tenantId;
        }
    }

    private static final class BusinessOnlyOrder extends TenantEntity<Long> {

        private OrderStatus status;

        private BusinessOnlyOrder(TenantId tenantId, long id, OrderStatus status) {
            super(id, tenantId);
            this.status = Objects.requireNonNull(status, "status");
        }

        static BusinessOnlyOrder place(TenantId tenantId, long id) {
            return new BusinessOnlyOrder(tenantId, id, OrderStatus.PENDING_STOCK);
        }

        void confirm() {
            status = OrderStatus.CONFIRMED;
        }

        OrderStatus status() {
            return status;
        }
    }

    private abstract static class AuditedBaseEntity<ID> {

        private final ID id;
        private final AuditMetadata audit;

        protected AuditedBaseEntity(ID id, AuditMetadata audit) {
            this.id = Objects.requireNonNull(id, "id");
            this.audit = Objects.requireNonNull(audit, "audit");
        }

        protected final ID id() {
            return id;
        }

        protected final AuditMetadata audit() {
            return audit;
        }
    }

    private abstract static class AuditedTenantEntity<ID> extends AuditedBaseEntity<ID> {

        private final TenantId tenantId;

        protected AuditedTenantEntity(ID id, TenantId tenantId, AuditMetadata audit) {
            super(id, audit);
            this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        }

        protected final TenantId tenantId() {
            return tenantId;
        }
    }

    private static final class PersistenceAuditedOrder extends AuditedTenantEntity<Long> {

        private OrderStatus status;

        private PersistenceAuditedOrder(
                TenantId tenantId, long id, OrderStatus status, AuditMetadata audit) {
            super(id, tenantId, audit);
            this.status = Objects.requireNonNull(status, "status");
        }

        static PersistenceAuditedOrder place(TenantId tenantId, long id) {
            return new PersistenceAuditedOrder(
                    tenantId, id, OrderStatus.PENDING_STOCK, AuditMetadata.unpersisted());
        }

        PersistenceAuditedOrder withAudit(AuditMetadata audit) {
            return new PersistenceAuditedOrder(tenantId(), id(), status, audit);
        }

        void confirm() {
            status = OrderStatus.CONFIRMED;
        }
    }

    private abstract static class ApplicationAuditedBaseEntity<ID> {

        private final ID id;
        private AuditMetadata audit;

        protected ApplicationAuditedBaseEntity(ID id, AuditMetadata audit) {
            this.id = Objects.requireNonNull(id, "id");
            this.audit = Objects.requireNonNull(audit, "audit");
        }

        protected final ID id() {
            return id;
        }

        protected final AuditMetadata audit() {
            return audit;
        }

        protected final void recordAudit(AuditStamp stamp) {
            audit = audit.updated(stamp);
        }
    }

    private abstract static class ApplicationAuditedTenantEntity<ID>
            extends ApplicationAuditedBaseEntity<ID> {

        private final TenantId tenantId;

        protected ApplicationAuditedTenantEntity(
                ID id, TenantId tenantId, AuditMetadata audit) {
            super(id, audit);
            this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        }

        protected final TenantId tenantId() {
            return tenantId;
        }
    }

    private static final class ApplicationAuditedOrder
            extends ApplicationAuditedTenantEntity<Long> {

        private OrderStatus status;

        private ApplicationAuditedOrder(
                TenantId tenantId, long id, OrderStatus status, AuditMetadata audit) {
            super(id, tenantId, audit);
            this.status = Objects.requireNonNull(status, "status");
        }

        static ApplicationAuditedOrder place(TenantId tenantId, long id, AuditStamp stamp) {
            return new ApplicationAuditedOrder(
                    tenantId, id, OrderStatus.PENDING_STOCK, AuditMetadata.created(stamp));
        }

        void confirm(AuditStamp stamp) {
            status = OrderStatus.CONFIRMED;
            recordAudit(stamp);
        }

        AuditMetadata currentAudit() {
            return audit();
        }

        OrderStatus status() {
            return status;
        }
    }

    private record AuditStamp(Instant at, String actorId) {

        private AuditStamp {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(actorId, "actorId");
        }
    }

    private record AuditMetadata(
            Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {

        static AuditMetadata unpersisted() {
            return new AuditMetadata(null, null, null, null);
        }

        static AuditMetadata created(AuditStamp stamp) {
            return new AuditMetadata(stamp.at(), stamp.actorId(), stamp.at(), stamp.actorId());
        }

        AuditMetadata updated(AuditStamp stamp) {
            if (createdAt == null) {
                return created(stamp);
            }
            return new AuditMetadata(createdAt, createdBy, stamp.at(), stamp.actorId());
        }
    }

    private static final class MutableAuditSource {

        private AuditStamp current;

        private MutableAuditSource(AuditStamp current) {
            this.current = current;
        }

        void use(AuditStamp next) {
            current = Objects.requireNonNull(next, "next");
        }

        AuditStamp current() {
            return current;
        }
    }

    private record Key(TenantId tenantId, long orderId) {}

    private record OrderRow(
            TenantId tenantId,
            long orderId,
            OrderStatus status,
            AuditMetadata audit) {}

    private static final class OutsideAuditRepository {

        private final MutableAuditSource auditSource;
        private final Map<Key, OrderRow> rows = new HashMap<>();

        private OutsideAuditRepository(MutableAuditSource auditSource) {
            this.auditSource = auditSource;
        }

        void save(BusinessOnlyOrder order) {
            Key key = new Key(order.tenantId(), order.id());
            OrderRow existing = rows.get(key);
            AuditMetadata audit = existing == null
                    ? AuditMetadata.created(auditSource.current())
                    : existing.audit().updated(auditSource.current());
            rows.put(key, new OrderRow(order.tenantId(), order.id(), order.status(), audit));
        }

        Optional<BusinessOnlyOrder> findById(TenantId tenantId, long orderId) {
            return Optional.ofNullable(rows.get(new Key(tenantId, orderId)))
                    .map(row -> new BusinessOnlyOrder(row.tenantId(), row.orderId(), row.status()));
        }

        Optional<AuditMetadata> auditOf(TenantId tenantId, long orderId) {
            return Optional.ofNullable(rows.get(new Key(tenantId, orderId))).map(OrderRow::audit);
        }
    }

    private static final class ReturningAuditedRepository {

        private final MutableAuditSource auditSource;
        private final Map<Key, OrderRow> rows = new HashMap<>();

        private ReturningAuditedRepository(MutableAuditSource auditSource) {
            this.auditSource = auditSource;
        }

        PersistenceAuditedOrder save(PersistenceAuditedOrder order) {
            Key key = new Key(order.tenantId(), order.id());
            OrderRow existing = rows.get(key);
            AuditMetadata audit = existing == null
                    ? AuditMetadata.created(auditSource.current())
                    : existing.audit().updated(auditSource.current());
            PersistenceAuditedOrder persisted = order.withAudit(audit);
            rows.put(key, new OrderRow(order.tenantId(), order.id(), order.status, audit));
            return persisted;
        }
    }

    private static final class VoidAuditedRepository {

        private final Map<Key, OrderRow> rows = new HashMap<>();

        void save(ApplicationAuditedOrder order) {
            rows.put(
                    new Key(order.tenantId(), order.id()),
                    new OrderRow(order.tenantId(), order.id(), order.status(), order.currentAudit()));
        }

        Optional<ApplicationAuditedOrder> findById(TenantId tenantId, long orderId) {
            return Optional.ofNullable(rows.get(new Key(tenantId, orderId)))
                    .map(row -> new ApplicationAuditedOrder(
                            row.tenantId(), row.orderId(), row.status(), row.audit()));
        }
    }
}
