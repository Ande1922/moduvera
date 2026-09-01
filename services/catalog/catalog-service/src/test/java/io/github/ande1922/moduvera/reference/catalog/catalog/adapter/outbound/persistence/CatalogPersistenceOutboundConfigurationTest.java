package io.github.ande1922.moduvera.reference.catalog.catalog.adapter.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.migration.MigrationDefinition;
import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.inbound.http.CatalogHttpController;
import io.github.ande1922.moduvera.reference.catalog.catalog.domain.ProductRepository;
import java.lang.reflect.Proxy;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;

class CatalogPersistenceOutboundConfigurationTest {

    @Test
    void explicitlyBindsOnlyTheMybatisRepositoryAdapter() {
        new ApplicationContextRunner()
                .withInitializer(context -> ((GenericApplicationContext) context)
                        .registerBean("catalogProductMapper", CatalogProductMapper.class, this::mapperStub))
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(CatalogPersistenceOutboundConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ProductRepository.class);
                    assertThat(context.getBean(ProductRepository.class))
                            .isInstanceOf(MybatisCatalogProductRepository.class);
                    assertThat(context).doesNotHaveBean(CatalogApi.class);
                    assertThat(context).doesNotHaveBean(CatalogHttpController.class);
                    assertThat(context).doesNotHaveBean(MigrationDefinition.class);
                });
    }

    private CatalogProductMapper mapperStub() {
        return (CatalogProductMapper) Proxy.newProxyInstance(
                CatalogProductMapper.class.getClassLoader(),
                new Class<?>[] {CatalogProductMapper.class},
                (proxy, method, arguments) -> null);
    }
}
