package io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence;

import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = InventoryMapper.class)
public class InventoryPersistenceConfiguration {

    @Bean
    InventoryStore inventoryStore(InventoryMapper mapper) {
        return new MybatisInventoryStore(mapper);
    }
}
