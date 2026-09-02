package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class IdentityServiceTokenClientConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withConfiguration(AutoConfigurations.of(
                    HttpClientAutoConfiguration.class,
                    ImperativeHttpClientAutoConfiguration.class,
                    HttpServiceClientPropertiesAutoConfiguration.class,
                    RestClientAutoConfiguration.class,
                    HttpServiceClientAutoConfiguration.class))
            .withUserConfiguration(IdentityServiceTokenClientConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withPropertyValues(
                    "moduvera.reference.clients.identity.service-id=order-service",
                    "moduvera.reference.clients.identity.service-secret=order-secret");

    @Test
    void rejectsMissingIdentityBaseUrlDuringStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("spring.http.serviceclient.identity.base-url");
        });
    }

    @Test
    void rejectsAConfiguredIdentityBaseUrlThatIsNotAbsoluteHttp() {
        contextRunner
                .withPropertyValues("spring.http.serviceclient.identity.base-url=/identity")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "spring.http.serviceclient.identity.base-url must be an absolute HTTP(S) URL");
                });
    }

    @Test
    void registersTheNamedIdentityGroupWithIndependentTimeouts() {
        contextRunner
                .withPropertyValues(
                        "spring.http.serviceclient.identity.base-url=https://identity.test",
                        "spring.http.serviceclient.identity.connect-timeout=125ms",
                        "spring.http.serviceclient.identity.read-timeout=750ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(InternalAccessTokenProvider.class);
                    assertThat(context).hasSingleBean(IdentityServiceTokenTransport.class);
                    var identity = context.getBean(HttpServiceClientProperties.class).get("identity");
                    assertThat(identity).isNotNull();
                    assertThat(identity.getBaseUrl()).isEqualTo("https://identity.test");
                    assertThat(identity.getConnectTimeout()).isEqualTo(Duration.ofMillis(125));
                    assertThat(identity.getReadTimeout()).isEqualTo(Duration.ofMillis(750));
                });
    }
}
