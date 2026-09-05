package io.github.ande1922.moduvera.reference.app.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.MissingExecutionContextException;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import io.github.ande1922.moduvera.migration.autoconfigure.ModuveraDatabaseMigrationAutoConfiguration;
import io.github.ande1922.moduvera.migration.autoconfigure.ModuveraDatabaseMigrationMode;
import io.github.ande1922.moduvera.migration.autoconfigure.ModuveraDatabaseMigrationProperties;
import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.inbound.http.CatalogHttpController;
import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.outbound.persistence.MybatisCatalogProductRepository;
import io.github.ande1922.moduvera.reference.catalog.catalog.domain.Product;
import io.github.ande1922.moduvera.reference.catalog.catalog.domain.ProductRepository;
import io.github.ande1922.moduvera.reference.catalog.migration.CatalogMigrationConfiguration;
import io.github.ande1922.moduvera.security.web.ExecutionContextHandlerSelection;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({CatalogApplicationIT.TestJwtConfiguration.class, CatalogApplicationIT.VirtualThreadProbe.class})
class CatalogApplicationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image", "postgres:18.6"));

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        initializeDisposableDatabase();
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.threads.virtual.enabled", () -> true);
    }

    private static void initializeDisposableDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ModuveraDatabaseMigrationAutoConfiguration.class))
                .withPropertyValues(
                        "moduvera.database.migration.mode=startup",
                        "moduvera.database.migration.initialize=true")
                .withBean(DataSource.class, () -> dataSource)
                .withUserConfiguration(CatalogMigrationConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProductRepository products;

    @Autowired
    private CatalogHttpController controller;

    @Autowired
    private List<MigrationDefinition> migrationDefinitions;

    @Autowired
    private ModuveraDatabaseMigrationProperties migrationProperties;

    @BeforeEach
    void seedProducts() {
        jdbc.update("DELETE FROM catalog_product");
        insert("tenant-a", 100L, "Keyboard", new BigDecimal("399.00"));
        insert("tenant-b", 200L, "Private Product", new BigDecimal("10.00"));
    }

    @Test
    void migrationUsesCanonicalTenantPersistenceLength() {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT character_maximum_length
                          FROM information_schema.columns
                         WHERE table_schema = 'public'
                           AND table_name = 'catalog_product'
                           AND column_name = 'tenant_id'
                        """,
                        Integer.class))
                .isEqualTo(64);
    }

    @Test
    void migrationRejectsOverlongHistoricalTenantBeforeChangingDataOrColumn() {
        String schema = "catalog_tenant_upgrade_test";
        String overlongTenant = "t".repeat(65);
        DataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate upgradeJdbc = new JdbcTemplate(upgradeDataSource);
        upgradeJdbc.execute("CREATE SCHEMA " + schema);
        try {
            Flyway.configure()
                    .dataSource(upgradeDataSource)
                    .locations("classpath:db/migration/catalog")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target(MigrationVersion.fromVersion("1"))
                    .load()
                    .migrate();
            upgradeJdbc.update(
                    """
                    INSERT INTO catalog_tenant_upgrade_test.catalog_product (
                        tenant_id, product_id, name, unit_price, currency, version,
                        created_at, created_by, updated_at, updated_by)
                    VALUES (?, 1, 'Legacy', 1.00, 'CNY', 0,
                        CURRENT_TIMESTAMP, 'test', CURRENT_TIMESTAMP, 'test')
                    """,
                    overlongTenant);

            assertThatThrownBy(() -> Flyway.configure()
                            .dataSource(upgradeDataSource)
                            .locations("classpath:db/migration/catalog")
                            .schemas(schema)
                            .defaultSchema(schema)
                            .load()
                            .migrate())
                    .hasStackTraceContaining(
                            "catalog tenant_id exceeds 64 characters; refusing to narrow persistence contract");
            assertThat(upgradeJdbc.queryForObject(
                            "SELECT tenant_id FROM catalog_tenant_upgrade_test.catalog_product WHERE product_id = 1",
                            String.class))
                    .isEqualTo(overlongTenant);
            assertThat(upgradeJdbc.queryForObject(
                            """
                            SELECT character_maximum_length
                              FROM information_schema.columns
                             WHERE table_schema = ?
                               AND table_name = 'catalog_product'
                               AND column_name = 'tenant_id'
                            """,
                            Integer.class,
                            schema))
                    .isEqualTo(128);
        } finally {
            upgradeJdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void exposesATenantSafeAuthenticatedCatalogContract() throws Exception {
        HttpResponse<String> found = get(100, "catalog-reader", "tenant-a", "corr-found");
        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(found.headers().firstValue("X-Correlation-Id")).contains("corr-found");
        assertThat(found.body())
                .contains("\"productId\":100")
                .contains("\"name\":\"Keyboard\"")
                .doesNotContain("\"data\"");

        HttpResponse<String> crossTenant = get(200, "catalog-reader", "tenant-a", "corr-cross");
        assertThat(crossTenant.statusCode()).isEqualTo(404);
        assertThat(crossTenant.body()).contains("\"code\":\"catalog.product-not-found\"");

        assertThat(get(100, null, "tenant-a", "corr-anonymous").statusCode()).isEqualTo(401);
        assertThat(get(100, "catalog-no-permission", "tenant-a", "corr-forbidden").statusCode())
                .isEqualTo(403);

        HttpResponse<String> invalid = get(0, "catalog-reader", "tenant-a", "corr-invalid");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body())
                .contains("\"code\":\"request.validation-failed\"")
                .contains("\"correlationId\":\"corr-invalid\"");

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM flyway_history_catalog WHERE success", Integer.class))
                .isPositive();
    }

    @Test
    void explicitlySelectsTheCatalogTopologyAndValidationOnlyMigrationPolicy() {
        assertThat(products).isInstanceOf(MybatisCatalogProductRepository.class);
        assertThat(controller).isNotNull();
        assertThat(migrationDefinitions)
                .extracting(definition -> definition.component().value())
                .containsExactly("catalog");
        assertThat(migrationProperties.getMode()).isEqualTo(ModuveraDatabaseMigrationMode.VALIDATE);
        assertThat(migrationProperties.isInitialize()).isFalse();
    }

    @Test
    void establishesAndClearsTenantContextOnVirtualHttpThreads() throws Exception {
        HttpResponse<String> tenantA = probe("tenant-a", "corr-virtual-a");
        HttpResponse<String> tenantB = probe("tenant-b", "corr-virtual-b");
        assertThat(tenantA.statusCode()).isEqualTo(200);
        assertThat(tenantA.body()).isEqualTo("tenant-a:true:corr-virtual-a");
        assertThat(tenantB.body()).isEqualTo("tenant-b:true:corr-virtual-b");
    }

    @Test
    void persistsReloadsAndAuditsProductsThroughTheProductionRepository() {
        var product = new Product(
                300, "Espresso", new BigDecimal("26.50"), Currency.getInstance("CNY"), 4);

        ExecutionContextHolder.run(context("tenant-a", "catalog-writer"), () -> products.save(product));
        Product reloaded = ExecutionContextHolder.call(
                context("tenant-a", "catalog-reader"),
                () -> products.findById(300).orElseThrow());

        assertThat(reloaded).isNotSameAs(product);
        assertThat(reloaded.id()).isEqualTo(300);
        assertThat(reloaded.name()).isEqualTo("Espresso");
        assertThat(reloaded.price()).isEqualByComparingTo("26.50");
        assertThat(reloaded.currency()).isEqualTo(Currency.getInstance("CNY"));
        assertThat(reloaded.version()).isEqualTo(4);

        Map<String, Object> audit = jdbc.queryForMap(
                """
                SELECT created_at, created_by, updated_at, updated_by
                  FROM catalog_product
                 WHERE tenant_id = 'tenant-a' AND product_id = 300
                """);
        assertThat(audit)
                .containsEntry("created_by", "catalog-writer")
                .containsEntry("updated_by", "catalog-writer");
        assertThat(audit.get("created_at")).isNotNull();
        assertThat(audit.get("updated_at")).isNotNull();
    }

    @Test
    void selectsProductsUsingOnlyTheCurrentTenantContext() {
        var tenantAProduct = new Product(
                301, "Coffee", new BigDecimal("18.00"), Currency.getInstance("CNY"), 3);
        var tenantBProduct = new Product(
                301, "Tea", new BigDecimal("12.00"), Currency.getInstance("CNY"), 2);

        ExecutionContextHolder.run(context("tenant-a", "writer-a"), () -> products.save(tenantAProduct));
        ExecutionContextHolder.run(context("tenant-b", "writer-b"), () -> products.save(tenantBProduct));

        Product tenantA = ExecutionContextHolder.call(
                context("tenant-a", "reader-a"), () -> products.findById(301).orElseThrow());
        Product tenantB = ExecutionContextHolder.call(
                context("tenant-b", "reader-b"), () -> products.findById(301).orElseThrow());
        var tenantC = ExecutionContextHolder.call(
                context("tenant-c", "reader-c"), () -> products.findById(301));

        assertThat(tenantA.name()).isEqualTo("Coffee");
        assertThat(tenantA.version()).isEqualTo(3);
        assertThat(tenantB.name()).isEqualTo("Tea");
        assertThat(tenantB.version()).isEqualTo(2);
        assertThat(tenantC).isEmpty();
    }

    @Test
    void failsClosedWithoutTenantContextAtTheProductionRepositorySeam() {
        var product = new Product(
                302, "Context Required", new BigDecimal("1.00"), Currency.getInstance("CNY"), 0);

        assertThatThrownBy(() -> products.findById(302))
                .isInstanceOf(MissingExecutionContextException.class);
        assertThatThrownBy(() -> products.save(product))
                .isInstanceOf(MissingExecutionContextException.class);
    }

    private HttpResponse<String> get(long productId, String token, String tenantId, String correlationId)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/internal/api/v1/catalog/products/" + productId))
                .header("X-Correlation-Id", correlationId)
                .header("Tenant-Id", tenantId)
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> probe(String tenantId, String correlationId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/test/virtual-context"))
                .header("Authorization", "Bearer catalog-reader")
                .header("Tenant-Id", tenantId)
                .header("X-Correlation-Id", correlationId)
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void insert(String tenantId, long productId, String name, BigDecimal price) {
        jdbc.update(
                """
                INSERT INTO catalog_product (
                    tenant_id, product_id, name, unit_price, currency, version,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, 'CNY', 0, CURRENT_TIMESTAMP, 'test', CURRENT_TIMESTAMP, 'test')
                """,
                tenantId,
                productId,
                name,
                price);
    }

    private static ExecutionContext context(String tenantId, String actorId) {
        return ExecutionContext.initiatedBy(
                new TenantId(tenantId), new Actor(ActorType.USER, actorId), "corr-" + tenantId + '-' + actorId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwtConfiguration {

        @Bean
        JwtDecoder catalogTestJwtDecoder() {
            return token -> switch (token) {
                case "catalog-reader" -> service(token, List.of("catalog:read"));
                case "catalog-no-permission" -> service(token, List.of());
                default -> throw new JwtException("unknown test token");
            };
        }

        @Bean
        @Primary
        ExecutionContextHandlerSelection catalogTestHttpExecutionHandlers() {
            return ExecutionContextHandlerSelection.builder()
                    .manageHandlers(CatalogHttpController.class, VirtualThreadProbe.class)
                    .excludePackage("org.springframework.boot.actuate")
                    .excludePackage("org.springframework.boot.autoconfigure.web.servlet.error")
                    .build();
        }

        private static Jwt service(String token, List<String> permissions) {
            Instant now = Instant.now();
            return Jwt.withTokenValue(token)
                    .header("alg", "test")
                    .subject("order-service")
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(3600))
                    .claim("actor_type", "SERVICE")
                    .claim("permissions", permissions)
                    .claim("initiator_type", "USER")
                    .claim("initiator_id", "alice")
                    .build();
        }
    }

    @RestController
    static class VirtualThreadProbe {

        @GetMapping("/test/virtual-context")
        String context() {
            var context = ExecutionContextHolder.require();
            return context.tenantId().value()
                    + ":"
                    + Thread.currentThread().isVirtual()
                    + ":"
                    + context.correlationId();
        }
    }
}
