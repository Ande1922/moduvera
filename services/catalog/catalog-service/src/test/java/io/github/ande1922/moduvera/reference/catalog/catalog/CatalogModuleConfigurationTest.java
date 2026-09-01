package io.github.ande1922.moduvera.reference.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.inbound.http.CatalogHttpController;
import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.outbound.persistence.CatalogProductMapper;
import io.github.ande1922.moduvera.reference.catalog.catalog.domain.Product;
import io.github.ande1922.moduvera.reference.catalog.catalog.domain.ProductRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CatalogModuleConfigurationTest {

    @Test
    void constructsOnlyTheProtocolNeutralCatalogApplicationService() {
        new ApplicationContextRunner()
                .withUserConfiguration(CatalogModuleConfiguration.class, ModuleDependencies.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(CatalogApi.class);
                    assertThat(context).doesNotHaveBean(CatalogHttpController.class);
                    assertThat(context).doesNotHaveBean(CatalogProductMapper.class);
                    assertThat(context).doesNotHaveBean(MigrationDefinition.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ModuleDependencies {

        @Bean
        ProductRepository productRepository() {
            return new ProductRepository() {
                @Override
                public Optional<Product> findById(long productId) {
                    return Optional.empty();
                }

                @Override
                public void save(Product product) {}
            };
        }

        @Bean
        UseCaseAuthorizer useCaseAuthorizer() {
            return new UseCaseAuthorizer();
        }
    }
}
