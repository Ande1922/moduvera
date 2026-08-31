package com.gaopc.platform.order.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaopc.platform.catalog.domain.Product;
import com.gaopc.platform.catalog.infrastructure.persistence.CatalogProductMapper;
import com.gaopc.platform.catalog.infrastructure.persistence.MybatisCatalogProductRepository;
import com.gaopc.platform.context.Actor;
import com.gaopc.platform.context.ActorType;
import com.gaopc.platform.context.ExecutionContext;
import com.gaopc.platform.context.ExecutionContextHolder;
import com.gaopc.platform.context.MissingExecutionContextException;
import com.gaopc.platform.context.TenantId;
import com.gaopc.platform.data.TransactionBoundary;
import com.gaopc.platform.inventory.api.InventoryRejected;
import com.gaopc.platform.inventory.api.InventoryReserved;
import com.gaopc.platform.inventory.api.ReserveInventoryCommand;
import com.gaopc.platform.inventory.api.ReserveInventoryLine;
import com.gaopc.platform.inventory.infrastructure.persistence.InventoryMapper;
import com.gaopc.platform.inventory.infrastructure.persistence.MybatisInventoryStore;
import com.gaopc.platform.migration.DatabaseComponent;
import com.gaopc.platform.migration.DatabaseMigrator;
import com.gaopc.platform.migration.MigrationPlan;
import com.gaopc.platform.messaging.kafka.autoconfigure.PlatformMessagingKafkaAutoConfiguration;
import com.gaopc.platform.order.domain.Order;
import com.gaopc.platform.order.domain.OrderLine;
import com.gaopc.platform.order.infrastructure.persistence.MybatisOrderRepository;
import com.gaopc.platform.order.infrastructure.persistence.OrderConcurrentModificationException;
import com.gaopc.platform.order.infrastructure.persistence.OrderMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
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
                    .isEqualTo(com.gaopc.platform.order.api.OrderStatus.PENDING_STOCK);
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

        ExecutionContextHolder.run(context("tenant-a"), () -> transactions.inTransaction(() -> {
            assertThat(inventory.reserve(rejected, NOW).result()).isInstanceOf(InventoryRejected.class);
            return null;
        }));
        assertThat(available("tenant-a", 7)).isEqualTo(5);
        assertThat(available("tenant-a", 8)).isEqualTo(1);

        ExecutionContextHolder.run(context("tenant-a"), () -> transactions.inTransaction(() -> {
            assertThat(inventory.reserve(reserved, NOW).result()).isInstanceOf(InventoryReserved.class);
            assertThat(inventory.reserve(reserved, NOW).created()).isFalse();
            return null;
        }));
        assertThat(available("tenant-a", 7)).isEqualTo(3);
        assertThat(available("tenant-b", 7)).isEqualTo(9);
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

    private static ExecutionContext context(String tenant) {
        return ExecutionContext.initiatedBy(
                new TenantId(tenant), new Actor(ActorType.USER, "mysql-tck"), "mysql-" + tenant);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = PlatformMessagingKafkaAutoConfiguration.class)
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
