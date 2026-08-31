package io.github.ande1922.moduvera.reference.app.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.threads.virtual.enabled", () -> true);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedProducts() {
        jdbc.update("DELETE FROM catalog_product");
        insert("tenant-a", 100L, "Keyboard", new BigDecimal("399.00"));
        insert("tenant-b", 200L, "Private Product", new BigDecimal("10.00"));
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
    void establishesAndClearsTenantContextOnVirtualHttpThreads() throws Exception {
        HttpResponse<String> tenantA = probe("tenant-a", "corr-virtual-a");
        HttpResponse<String> tenantB = probe("tenant-b", "corr-virtual-b");
        assertThat(tenantA.statusCode()).isEqualTo(200);
        assertThat(tenantA.body()).isEqualTo("tenant-a:true:corr-virtual-a");
        assertThat(tenantB.body()).isEqualTo("tenant-b:true:corr-virtual-b");
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
