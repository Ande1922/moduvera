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
import io.github.ande1922.moduvera.context.ExecutionScope;
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
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
        assertTenantColumnLength("catalog_product");
        assertTenantColumnLength("order_header");
        assertTenantColumnLength("order_line");
        assertTenantColumnLength("inventory_stock");
        assertTenantColumnLength("inventory_reservation_result");
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
    void rejectsPlatformAndMissingScopesBeforeTenantReadsAndWritesOnMySql() {
        var existing = new Product(
                901, "Existing", new BigDecimal("19.00"), Currency.getInstance("CNY"), 0);
        var rejected = new Product(
                902, "Rejected", new BigDecimal("29.00"), Currency.getInstance("CNY"), 0);
        ExecutionContextHolder.run(context("tenant-a"), () -> catalog.save(existing));
        int rowsBeforeRejectedWrites = catalogRowCount();
        var platform = platformContext();

        assertThatThrownBy(() -> ExecutionContextHolder.call(
                        platform, () -> catalog.findById(existing.id())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tenant execution scope is required at this boundary");
        assertThatThrownBy(() -> catalog.findById(existing.id()))
                .isInstanceOf(MissingExecutionContextException.class);

        assertThatThrownBy(() -> ExecutionContextHolder.run(platform, () -> catalog.save(rejected)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tenant execution scope is required at this boundary");
        assertThatThrownBy(() -> catalog.save(rejected))
                .isInstanceOf(MissingExecutionContextException.class);
        assertThat(catalogRowCount()).isEqualTo(rowsBeforeRejectedWrites);
        assertThat(catalogRowCount(rejected.id())).isZero();

        assertThat(ExecutionContextHolder.call(
                        context("tenant-a"), () -> catalog.findById(existing.id())))
                .get()
                .satisfies(found -> {
                    assertThat(found.id()).isEqualTo(existing.id());
                    assertThat(found.name()).isEqualTo(existing.name());
                    assertThat(found.price()).isEqualByComparingTo(existing.price());
                    assertThat(found.currency()).isEqualTo(existing.currency());
                    assertThat(found.version()).isEqualTo(existing.version());
                });
        assertThat(ExecutionContextHolder.call(
                        context("tenant-b"), () -> catalog.findById(existing.id())))
                .isEmpty();
        assertThat(ExecutionContextHolder.current()).isEmpty();
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

    @Test
    void catalogMigrationRejectsOverlongV1DataBeforeNarrowingAndPreservesCompatibleData() {
        String schema = "catalog_tenant_upgrade_test";
        String overlongTenant = "t".repeat(65);
        String compatibleTenant = "t".repeat(64);
        JdbcTemplate upgradeJdbc = catalogUpgradeJdbc();
        resetDatabase(upgradeJdbc, schema);
        try {
            migrateCatalogToV1(schema);
            insertCatalogUpgradeProduct(upgradeJdbc, schema, overlongTenant, 1);

            assertThatThrownBy(() -> migrateCatalogLatest(schema))
                    .hasStackTraceContaining(
                            "catalog tenant_id exceeds 64 characters; refusing to narrow persistence contract");
            assertThat(catalogUpgradeTenant(upgradeJdbc, schema, 1)).isEqualTo(overlongTenant);
            assertThat(catalogTenantColumnLength(upgradeJdbc, schema)).isEqualTo(128);

            resetDatabase(upgradeJdbc, schema);
            migrateCatalogToV1(schema);
            insertCatalogUpgradeProduct(upgradeJdbc, schema, compatibleTenant, 2);

            migrateCatalogLatest(schema);

            assertThat(catalogUpgradeTenant(upgradeJdbc, schema, 2)).isEqualTo(compatibleTenant);
            assertThat(catalogTenantColumnLength(upgradeJdbc, schema)).isEqualTo(64);
        } finally {
            upgradeJdbc.execute("DROP DATABASE IF EXISTS " + schema);
        }
    }

    private static void migrate(DatabaseMigrator migrator, String component, String location) {
        migrator.migrate(new MigrationPlan(new DatabaseComponent(component), List.of(location), true));
        assertThat(migrator.validate(
                                new MigrationPlan(new DatabaseComponent(component), List.of(location), false))
                        .validationSuccessful)
                .isTrue();
    }

    private void migrateCatalogToV1(String schema) {
        catalogFlyway(schema, MigrationVersion.fromVersion("1")).migrate();
    }

    private void migrateCatalogLatest(String schema) {
        catalogFlyway(schema, null).migrate();
    }

    private Flyway catalogFlyway(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(catalogUpgradeDataSource())
                .locations("classpath:db/migration/catalog-mysql")
                .schemas(schema)
                .defaultSchema(schema);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static DataSource catalogUpgradeDataSource() {
        return new DriverManagerDataSource(
                "jdbc:mysql://%s:%d/mysql".formatted(MYSQL.getHost(), MYSQL.getFirstMappedPort()),
                "root",
                MYSQL.getPassword());
    }

    private static JdbcTemplate catalogUpgradeJdbc() {
        return new JdbcTemplate(catalogUpgradeDataSource());
    }

    private static void resetDatabase(JdbcTemplate upgradeJdbc, String schema) {
        upgradeJdbc.execute("DROP DATABASE IF EXISTS " + schema);
        upgradeJdbc.execute("CREATE DATABASE " + schema);
    }

    private static void insertCatalogUpgradeProduct(
            JdbcTemplate upgradeJdbc, String schema, String tenant, long productId) {
        upgradeJdbc.update(
                """
                INSERT INTO %s.catalog_product(
                    tenant_id, product_id, name, unit_price, currency, version,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, 'Legacy', 1.00, 'CNY', 0,
                    CURRENT_TIMESTAMP, 'test', CURRENT_TIMESTAMP, 'test')
                """
                        .formatted(schema),
                tenant,
                productId);
    }

    private static String catalogUpgradeTenant(
            JdbcTemplate upgradeJdbc, String schema, long productId) {
        return upgradeJdbc.queryForObject(
                "SELECT tenant_id FROM %s.catalog_product WHERE product_id = ?".formatted(schema),
                String.class,
                productId);
    }

    private static int catalogTenantColumnLength(JdbcTemplate upgradeJdbc, String schema) {
        return upgradeJdbc.queryForObject(
                """
                SELECT character_maximum_length
                  FROM information_schema.columns
                 WHERE table_schema = ?
                   AND table_name = 'catalog_product'
                   AND column_name = 'tenant_id'
                """,
                Integer.class,
                schema);
    }

    private void assertTenantColumnLength(String table) {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT character_maximum_length
                          FROM information_schema.columns
                         WHERE table_schema = database()
                           AND table_name = ?
                           AND column_name = 'tenant_id'
                        """,
                        Integer.class,
                        table))
                .isEqualTo(64);
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

    private int catalogRowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product", Integer.class);
    }

    private int catalogRowCount(long productId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM catalog_product WHERE product_id = ?", Integer.class, productId);
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

    private static ExecutionContext platformContext() {
        return ExecutionContext.initiatedBy(
                ExecutionScope.platform(),
                new Actor(ActorType.SYSTEM, "mysql-platform-probe"),
                "mysql-platform");
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
