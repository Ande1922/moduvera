package com.gaopc.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaopc.platform.authorization.UseCaseAuthorizer;
import com.gaopc.platform.catalog.api.CatalogApi;
import com.gaopc.platform.catalog.api.ProductSnapshot;
import com.gaopc.platform.catalog.domain.Product;
import com.gaopc.platform.catalog.domain.ProductRepository;
import com.gaopc.platform.catalog.infrastructure.persistence.CatalogProductMapper;
import com.gaopc.platform.catalog.infrastructure.persistence.MybatisCatalogProductRepository;
import com.gaopc.platform.catalog.inbound.http.CatalogHttpController;
import com.gaopc.platform.web.ProblemStatusResolver;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CatalogConfigurationSlicesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    void applicationSliceActivatesOnlyTheLocalCatalogApi() {
        contextRunner
                .withUserConfiguration(
                        CatalogApplicationConfiguration.class, ApplicationDependencies.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(CatalogApi.class);
                    assertThat(context).doesNotHaveBean(CatalogHttpController.class);
                    assertThat(context).doesNotHaveBean(CatalogProductMapper.class);
                    assertThat(context).doesNotHaveBean(ProblemStatusResolver.class);
                });
    }

    @Test
    void persistenceSliceExplicitlyBindsTheRepositoryPort() {
        CatalogProductMapper mapper = mapperStub();

        ProductRepository repository =
                new CatalogPersistenceConfiguration().catalogProductRepository(mapper, Clock.systemUTC());

        assertThat(repository)
                .isInstanceOf(MybatisCatalogProductRepository.class)
                .isNotSameAs(mapper);
    }

    @Test
    void internalHttpSliceActivatesOnlyTheControllerAndProviderErrorMapping() {
        contextRunner
                .withUserConfiguration(
                        CatalogInternalHttpConfiguration.class, InternalHttpDependencies.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(CatalogHttpController.class);
                    assertThat(context).hasSingleBean(ProblemStatusResolver.class);
                    assertThat(context).doesNotHaveBean(ProductRepository.class);
                    assertThat(context).doesNotHaveBean(CatalogProductMapper.class);
                });
    }

    private static CatalogProductMapper mapperStub() {
        return (CatalogProductMapper) Proxy.newProxyInstance(
                CatalogProductMapper.class.getClassLoader(),
                new Class<?>[] {CatalogProductMapper.class},
                (proxy, method, arguments) -> null);
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationDependencies {

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

    @Configuration(proxyBeanMethods = false)
    static class InternalHttpDependencies {

        @Bean
        CatalogApi catalogApi() {
            return query -> new ProductSnapshot(
                    query.productId(), "test", BigDecimal.ZERO, Currency.getInstance("CNY"), 0);
        }
    }
}
