package io.github.ande1922.moduvera.reference.order.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.outbound.persistence.CatalogProductMapper;
import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.outbound.persistence.MybatisCatalogProductRepository;
import io.github.ande1922.moduvera.reference.catalog.catalog.domain.Product;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.MissingExecutionContextException;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import io.github.ande1922.moduvera.reference.inventory.domain.AllOrNothingReservationPolicy;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationDecision;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationExecution;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationPolicy;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence.InventoryMapper;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence.MybatisInventoryStore;
import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationPlan;
import io.github.ande1922.moduvera.messaging.kafka.autoconfigure.ModuveraMessagingKafkaAutoConfiguration;
import io.github.ande1922.moduvera.reference.order.domain.Order;
import io.github.ande1922.moduvera.reference.order.domain.OrderLine;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence.MybatisOrderRepository;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence.OrderConcurrentModificationException;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence.OrderMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = MySqlBusinessRepositoriesIT.TestApplication.class)
class MySqlBusinessRepositoriesIT {

    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");
    private static final ReservationPolicy RESERVATION_POLICY =
            new AllOrNothingReservationPolicy();

    @Container
    private static final MySQLContainer MYSQL =
            new MySQLContainer(System.getProperty("mysql.test.image", "mysql:8.4.11"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.flyway.enabled", () -> false);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MybatisCatalogProductRepository catalog;

    @Autowired
    private MybatisOrderRepository orders;

    @Autowired
    private MybatisInventoryStore inventory;

    @Autowired
    private TransactionBoundary transactions;

    @BeforeAll
    void migrateAllComponents() {
        var migrator = new DatabaseMigrator(dataSource);
        migrate(migrator, "catalog", "classpath:db/migration/catalog-mysql");
        migrate(migrator, "order", "classpath:db/migration/order-mysql");
        migrate(migrator, "inventory", "classpath:db/migration/inventory-mysql");
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM inventory_reservation_result");
        jdbc.update("DELETE FROM inventory_stock");
        jdbc.update("DELETE FROM order_line");
        jdbc.update("DELETE FROM order_header");
        jdbc.update("DELETE FROM catalog_product");
    }

    @Test
    void qualifiesCatalogAndOrderRehydrateTenantIsolationVersionAndRollback() {
        ExecutionContextHolder.run(context("tenant-a"), () -> {
            catalog.save(new Product(100, "Keyboard", new BigDecimal("399.00"), Currency.getInstance("CNY"), 0));
            Order order = Order.place(
                    42,
                    List.of(new OrderLine(100, "Keyboard", 2, new BigDecimal("399.00"))),
                    Currency.getInstance("CNY"),
                    NOW);
            orders.save(order);
            assertThat(orders.findById(42)).get().extracting(Order::status)
                    .isEqualTo(io.github.ande1922.moduvera.reference.order.api.OrderStatus.PENDING_STOCK);
        });

        ExecutionContextHolder.run(context("tenant-b"), () -> {
            assertThat(catalog.findById(100)).isEmpty();
            assertThat(orders.findById(42)).isEmpty();
        });
        assertThatThrownBy(() -> orders.findById(42)).isInstanceOf(MissingExecutionContextException.class);

        ExecutionContextHolder.run(context("tenant-a"), () -> {
            Order first = orders.findById(42).orElseThrow();
            Order stale = orders.findById(42).orElseThrow();
            first.apply(new InventoryReserved("reserve-order-42", 42, NOW));
            orders.save(first);
            stale.apply(new InventoryRejected("reserve-order-42", 42, List.of(100L), NOW));
            assertThatThrownBy(() -> orders.save(stale))
                    .isInstanceOf(OrderConcurrentModificationException.class);
        });

        assertThatThrownBy(() -> ExecutionContextHolder.run(context("tenant-a"), () ->
                        transactions.inTransaction(() -> {
                            Order rollback = Order.place(
                                    99,
                                    List.of(new OrderLine(100, "Keyboard", 1, new BigDecimal("399.00"))),
                                    Currency.getInstance("CNY"),
                                    NOW);
                            orders.save(rollback);
                            throw new IllegalStateException("rollback");
                        })))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_header WHERE order_id = 99", Integer.class))
                .isZero();
    }

    @Test
    void qualifiesInventoryAtomicReservationIdempotencyAndTenantIsolation() {
        seedStock("tenant-a", 7, 5);
        seedStock("tenant-a", 8, 1);
        seedStock("tenant-b", 7, 9);
        var rejected = new ReserveInventoryCommand(
                "reserve-rejected", 70, List.of(new ReserveInventoryLine(7, 2), new ReserveInventoryLine(8, 2)));
        var reserved = new ReserveInventoryCommand(
                "reserve-ok", 71, List.of(new ReserveInventoryLine(7, 2)));

        ReservationExecution rejectedDecision = reserve("tenant-a", rejected, NOW);
        assertThat(rejectedDecision.created()).isTrue();
        assertThat(rejectedDecision.decision())
                .isEqualTo(ReservationDecision.rejected(List.of(8L)));
        assertThat(available("tenant-a", 7)).isEqualTo(5);
        assertThat(available("tenant-a", 8)).isEqualTo(1);

        ReservationExecution reconstructedRejection =
                reserve("tenant-a", rejected, NOW.plusSeconds(1));
        assertThat(reconstructedRejection)
                .isEqualTo(new ReservationExecution(
                        rejected.commandId(),
                        rejected.orderId(),
                        rejectedDecision.decision(),
                        NOW,
                        false));

        ReservationExecution reservedDecision = reserve("tenant-a", reserved, NOW);
        assertThat(reservedDecision.created()).isTrue();
        assertThat(reservedDecision.decision()).isEqualTo(ReservationDecision.reserved());
        ReservationExecution reconstructedReservation =
                reserve("tenant-a", reserved, NOW.plusSeconds(1));
        assertThat(reconstructedReservation)
                .isEqualTo(new ReservationExecution(
                        reserved.commandId(),
                        reserved.orderId(),
                        reservedDecision.decision(),
                        NOW,
                        false));
        assertThat(available("tenant-a", 7)).isEqualTo(3);
        assertThat(available("tenant-b", 7)).isEqualTo(9);

        ReservationExecution sameIdentityInOtherTenant = reserve("tenant-b", reserved, NOW);
        assertThat(sameIdentityInOtherTenant.created()).isTrue();
        assertThat(sameIdentityInOtherTenant.decision()).isEqualTo(reservedDecision.decision());
        assertThat(available("tenant-a", 7)).isEqualTo(3);
        assertThat(available("tenant-b", 7)).isEqualTo(7);
    }

    @Test
    void qualifiesInventoryConcurrentCompetitionAndResultReconstruction() throws Exception {
        seedStock("tenant-a", 7, 5);
        var firstCommand = new ReserveInventoryCommand(
                "concurrent-1", 501, List.of(new ReserveInventoryLine(7, 4)));
        var secondCommand = new ReserveInventoryCommand(
                "concurrent-2", 502, List.of(new ReserveInventoryLine(7, 4)));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        ReservationExecution firstDecision;
        ReservationExecution secondDecision;
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> reserveTogether(ready, start, firstCommand));
            var second = workers.submit(() -> reserveTogether(ready, start, secondCommand));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            firstDecision = first.get(5, TimeUnit.SECONDS);
            secondDecision = second.get(5, TimeUnit.SECONDS);
        }

        assertThat(List.of(firstDecision.decision(), secondDecision.decision()))
                .filteredOn(ReservationDecision::isReserved)
                .hasSize(1);
        assertThat(List.of(firstDecision.decision(), secondDecision.decision()))
                .filteredOn(decision -> !decision.isReserved())
                .hasSize(1);
        assertThat(available("tenant-a", 7)).isEqualTo(1);

        ReservationExecution reconstructedFirst =
                reserve("tenant-a", firstCommand, NOW.plusSeconds(1));
        ReservationExecution reconstructedSecond =
                reserve("tenant-a", secondCommand, NOW.plusSeconds(1));
        assertThat(reconstructedFirst)
                .isEqualTo(new ReservationExecution(
                        firstCommand.commandId(),
                        firstCommand.orderId(),
                        firstDecision.decision(),
                        firstDecision.decidedAt(),
                        false));
        assertThat(reconstructedSecond)
                .isEqualTo(new ReservationExecution(
                        secondCommand.commandId(),
                        secondCommand.orderId(),
                        secondDecision.decision(),
                        secondDecision.decidedAt(),
                        false));
    }

    private static void migrate(DatabaseMigrator migrator, String component, String location) {
        migrator.migrate(new MigrationPlan(new DatabaseComponent(component), List.of(location), true));
        assertThat(migrator.validate(
                                new MigrationPlan(new DatabaseComponent(component), List.of(location), false))
                        .validationSuccessful)
                .isTrue();
    }

    private void seedStock(String tenant, long product, int available) {
        jdbc.update(
                """
                INSERT INTO inventory_stock(
                    tenant_id, product_id, available, version,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, 0, ?, 'test', ?, 'test')
                """,
                tenant,
                product,
                available,
                NOW,
                NOW);
    }

    private int available(String tenant, long product) {
        return jdbc.queryForObject(
                "SELECT available FROM inventory_stock WHERE tenant_id = ? AND product_id = ?",
                Integer.class,
                tenant,
                product);
    }

    private ReservationExecution reserve(
            String tenant, ReserveInventoryCommand command, Instant now) {
        return ExecutionContextHolder.call(
                context(tenant), () -> transactions.inTransaction(() -> inventory.reserve(
                        command, now, RESERVATION_POLICY)));
    }

    private ReservationExecution reserveTogether(
            CountDownLatch ready, CountDownLatch start, ReserveInventoryCommand command)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return reserve("tenant-a", command, NOW);
    }

    private static ExecutionContext context(String tenant) {
        return ExecutionContext.initiatedBy(
                new TenantId(tenant), new Actor(ActorType.USER, "mysql-tck"), "mysql-" + tenant);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = ModuveraMessagingKafkaAutoConfiguration.class)
    @Configuration(proxyBeanMethods = false)
    @MapperScan(basePackageClasses = {CatalogProductMapper.class, OrderMapper.class, InventoryMapper.class})
    static class TestApplication {

        @Bean
        Clock mysqlTckClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        MybatisCatalogProductRepository mysqlCatalogRepository(CatalogProductMapper mapper, Clock clock) {
            return new MybatisCatalogProductRepository(mapper, clock);
        }

        @Bean
        MybatisOrderRepository mysqlOrderRepository(OrderMapper mapper, Clock clock) {
            return new MybatisOrderRepository(mapper, clock);
        }

        @Bean
        MybatisInventoryStore mysqlInventoryStore(InventoryMapper mapper) {
            return new MybatisInventoryStore(mapper);
        }
    }
}
