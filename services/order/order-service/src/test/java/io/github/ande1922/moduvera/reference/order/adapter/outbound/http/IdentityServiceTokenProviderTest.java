package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.TenantId;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

class IdentityServiceTokenProviderTest {

    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void obtainsAServiceTokenWithBasicCredentialsAndDelegatedContext() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://identity.test");
        MockRestServiceServer identity = MockRestServiceServer.bindTo(builder).build();
        identity.expect(requestTo("https://identity.test/internal/api/v1/service-token"))
                .andExpect(header(
                        "Authorization", "Basic b3JkZXItc2VydmljZTpvcmRlci1zZWNyZXQ="))
                .andExpect(content().json("""
                        {
                          "tenantId": "tenant-a",
                          "audience": "catalog-service",
                          "initiatorType": "USER",
                          "initiatorId": "alice"
                        }
                        """))
                .andRespond(withSuccess(
                        "{\"accessToken\":\"catalog-token\",\"expiresAt\":\"2026-09-02T00:05:00Z\"}",
                        MediaType.APPLICATION_JSON));
        var provider = provider(builder);

        String token = ExecutionContextHolder.call(context(), provider::accessToken);

        assertThat(token).isEqualTo("catalog-token");
        identity.verify();
    }

    @Test
    void rejectsAnEmptyIdentityResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://identity.test");
        MockRestServiceServer identity = MockRestServiceServer.bindTo(builder).build();
        identity.expect(requestTo("https://identity.test/internal/api/v1/service-token"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        var provider = provider(builder);

        assertThatThrownBy(() -> ExecutionContextHolder.call(context(), provider::accessToken))
                .isInstanceOf(CatalogCallException.class)
                .hasMessage("Identity returned an empty service token");
        identity.verify();
    }

    @Test
    void convertsAnIdentityRejectionToTheExistingOrderFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://identity.test");
        MockRestServiceServer identity = MockRestServiceServer.bindTo(builder).build();
        identity.expect(requestTo("https://identity.test/internal/api/v1/service-token"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        var provider = provider(builder);

        assertThatThrownBy(() -> ExecutionContextHolder.call(context(), provider::accessToken))
                .isInstanceOf(CatalogCallException.class)
                .hasMessage("Identity rejected the service token request with HTTP 503")
                .hasCauseInstanceOf(org.springframework.web.client.RestClientResponseException.class);
        identity.verify();
    }

    @Test
    void convertsAnIdentityTransportFailureToTheExistingOrderFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://identity.test");
        MockRestServiceServer identity = MockRestServiceServer.bindTo(builder).build();
        identity.expect(requestTo("https://identity.test/internal/api/v1/service-token"))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });
        var provider = provider(builder);

        assertThatThrownBy(() -> ExecutionContextHolder.call(context(), provider::accessToken))
                .isInstanceOf(CatalogCallException.class)
                .hasMessage("Identity service token request failed")
                .hasCauseInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        identity.verify();
    }

    @Test
    void rejectsPlatformWithoutCallingIdentity() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://identity.test");
        MockRestServiceServer identity = MockRestServiceServer.bindTo(builder).build();
        var provider = provider(builder);
        var platform = ExecutionContext.initiatedBy(
                ExecutionScope.platform(), new Actor(ActorType.USER, "operator"), "corr-platform-identity");

        assertThatThrownBy(() -> ExecutionContextHolder.call(platform, provider::accessToken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tenant execution scope is required at this boundary");
        identity.verify();
    }

    private static IdentityServiceTokenProvider provider(RestClient.Builder builder) {
        RestClient client = builder.build();
        var transport = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(IdentityServiceTokenTransport.class);
        return new IdentityServiceTokenProvider(
                transport,
                "order-service",
                "order-secret",
                "catalog-service",
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30),
                32);
    }

    private static ExecutionContext context() {
        return ExecutionContext.initiatedBy(
                new TenantId("tenant-a"), new Actor(ActorType.USER, "alice"), "corr-identity");
    }
}
