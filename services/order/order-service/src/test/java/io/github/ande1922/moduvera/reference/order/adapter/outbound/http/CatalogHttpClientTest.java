package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.reference.catalog.api.GetProductQuery;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

class CatalogHttpClientTest {

    @Test
    void looksUpAProductWithTrustedServiceTenantAndCorrelationHeaders() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://catalog.test");
        MockRestServiceServer catalog = MockRestServiceServer.bindTo(builder).build();
        catalog.expect(requestTo("https://catalog.test/internal/api/v1/catalog/products/100"))
                .andExpect(header("Authorization", "Bearer catalog-token"))
                .andExpect(header("Tenant-Id", "tenant-a"))
                .andExpect(header("X-Correlation-Id", "corr-catalog"))
                .andRespond(withSuccess(
                        """
                        {
                          "productId": 100,
                          "name": "Keyboard",
                          "unitPrice": 399.00,
                          "currency": "CNY",
                          "version": 3
                        }
                        """,
                        MediaType.APPLICATION_JSON));
        CatalogHttpClient client = client(builder);

        var snapshot = ExecutionContextHolder.call(
                context(), () -> client.getProduct(new GetProductQuery(100)));

        assertThat(snapshot.productId()).isEqualTo(100);
        assertThat(snapshot.name()).isEqualTo("Keyboard");
        assertThat(snapshot.unitPrice()).isEqualByComparingTo(new BigDecimal("399.00"));
        assertThat(snapshot.currency()).isEqualTo(Currency.getInstance("CNY"));
        assertThat(snapshot.version()).isEqualTo(3);
        catalog.verify();
    }

    @Test
    void convertsAnEmptyCatalogResponseToTheExistingOrderFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://catalog.test");
        MockRestServiceServer catalog = MockRestServiceServer.bindTo(builder).build();
        catalog.expect(requestTo("https://catalog.test/internal/api/v1/catalog/products/100"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        CatalogHttpClient client = client(builder);

        assertThatThrownBy(() -> ExecutionContextHolder.call(
                        context(), () -> client.getProduct(new GetProductQuery(100))))
                .isInstanceOf(CatalogCallException.class)
                .hasMessage("Catalog returned an empty product snapshot");
        catalog.verify();
    }

    @Test
    void convertsACatalogRejectionToTheExistingOrderFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://catalog.test");
        MockRestServiceServer catalog = MockRestServiceServer.bindTo(builder).build();
        catalog.expect(requestTo("https://catalog.test/internal/api/v1/catalog/products/100"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        CatalogHttpClient client = client(builder);

        assertThatThrownBy(() -> ExecutionContextHolder.call(
                        context(), () -> client.getProduct(new GetProductQuery(100))))
                .isInstanceOf(CatalogCallException.class)
                .hasMessage("Catalog rejected the product lookup with HTTP 503")
                .hasCauseInstanceOf(org.springframework.web.client.RestClientResponseException.class);
        catalog.verify();
    }

    @Test
    void convertsACatalogTransportFailureToTheExistingOrderFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://catalog.test");
        MockRestServiceServer catalog = MockRestServiceServer.bindTo(builder).build();
        catalog.expect(requestTo("https://catalog.test/internal/api/v1/catalog/products/100"))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });
        CatalogHttpClient client = client(builder);

        assertThatThrownBy(() -> ExecutionContextHolder.call(
                        context(), () -> client.getProduct(new GetProductQuery(100))))
                .isInstanceOf(CatalogCallException.class)
                .hasMessage("Catalog product lookup failed")
                .hasCauseInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        catalog.verify();
    }

    private static CatalogHttpClient client(RestClient.Builder builder) {
        RestClient client = builder.build();
        var transport = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(CatalogLookupTransport.class);
        return new CatalogHttpClient(transport, () -> "catalog-token");
    }

    private static ExecutionContext context() {
        return ExecutionContext.initiatedBy(
                new TenantId("tenant-a"), new Actor(ActorType.USER, "alice"), "corr-catalog");
    }
}
