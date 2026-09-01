package io.github.ande1922.moduvera.reference.catalog.catalog.adapter.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.migration.MigrationDefinition;
import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.api.ProductSnapshot;
import io.github.ande1922.moduvera.reference.catalog.catalog.domain.ProductRepository;
import io.github.ande1922.moduvera.web.ProblemStatusContributor;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CatalogInternalHttpInboundConfigurationTest {

    @Test
    void activatesOnlyTheControllerAndProviderErrorMapping() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        CatalogInternalHttpInboundConfiguration.class,
                        HttpDependencies.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(CatalogHttpController.class);
                    assertThat(context).hasSingleBean(ProblemStatusContributor.class);
                    assertThat(context).doesNotHaveBean(ProductRepository.class);
                    assertThat(context).doesNotHaveBean(MigrationDefinition.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class HttpDependencies {

        @Bean
        CatalogApi catalogApi() {
            return query -> new ProductSnapshot(
                    query.productId(), "test", BigDecimal.ZERO, Currency.getInstance("CNY"), 0);
        }
    }
}
