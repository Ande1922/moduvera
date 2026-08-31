package io.github.ande1922.moduvera.reference.catalog;

import io.github.ande1922.moduvera.reference.catalog.domain.ProductRepository;
import io.github.ande1922.moduvera.reference.catalog.infrastructure.persistence.CatalogProductMapper;
import io.github.ande1922.moduvera.reference.catalog.infrastructure.persistence.MybatisCatalogProductRepository;
import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationPlan;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = CatalogProductMapper.class)
public class CatalogPersistenceConfiguration {

    @Bean
    ProductRepository catalogProductRepository(CatalogProductMapper mapper, Clock clock) {
        return new MybatisCatalogProductRepository(mapper, clock);
    }

    @Bean
    SmartInitializingSingleton catalogMigration(DataSource dataSource) {
        return () -> new DatabaseMigrator(dataSource)
                .migrate(new MigrationPlan(
                        new DatabaseComponent("catalog"),
                        List.of("classpath:db/migration/catalog"),
                        true));
    }
}
