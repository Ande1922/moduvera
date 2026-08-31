package com.gaopc.platform.catalog;

import com.gaopc.platform.catalog.domain.ProductRepository;
import com.gaopc.platform.catalog.infrastructure.persistence.CatalogProductMapper;
import com.gaopc.platform.catalog.infrastructure.persistence.MybatisCatalogProductRepository;
import com.gaopc.platform.migration.DatabaseComponent;
import com.gaopc.platform.migration.DatabaseMigrator;
import com.gaopc.platform.migration.MigrationPlan;
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
