package io.github.ande1922.moduvera.reference.catalog.catalog.adapter.outbound.persistence;

import io.github.ande1922.moduvera.reference.catalog.catalog.domain.ProductRepository;
import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = CatalogProductMapper.class)
public class CatalogPersistenceOutboundConfiguration {

    @Bean
    ProductRepository catalogProductRepository(CatalogProductMapper mapper, Clock clock) {
        return new MybatisCatalogProductRepository(mapper, clock);
    }
}
