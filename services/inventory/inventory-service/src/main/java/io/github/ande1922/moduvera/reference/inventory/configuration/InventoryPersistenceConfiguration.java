package io.github.ande1922.moduvera.reference.inventory.configuration;

import io.github.ande1922.moduvera.reference.inventory.application.InventoryResultPublisher;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import io.github.ande1922.moduvera.reference.inventory.infrastructure.messaging.OutboxInventoryResultPublisher;
import io.github.ande1922.moduvera.reference.inventory.infrastructure.persistence.InventoryMapper;
import io.github.ande1922.moduvera.reference.inventory.infrastructure.persistence.MybatisInventoryStore;
import io.github.ande1922.moduvera.message.publication.DurablePublication;
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
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = InventoryMapper.class)
public class InventoryPersistenceConfiguration {

    @Bean
    InventoryStore inventoryStore(InventoryMapper mapper) {
        return new MybatisInventoryStore(mapper);
    }

    @Bean
    InventoryResultPublisher inventoryResultPublisher(
            DurablePublication outbox, ObjectMapper json, Clock clock) {
        return new OutboxInventoryResultPublisher(outbox, json, clock);
    }

    @Bean
    SmartInitializingSingleton inventoryMigrations(DataSource dataSource) {
        return () -> {
            var migrator = new DatabaseMigrator(dataSource);
            migrator.migrate(new MigrationPlan(
                    new DatabaseComponent("inventory"),
                    List.of("classpath:db/migration/inventory"),
                    true));
            migrator.migrate(new MigrationPlan(
                    new DatabaseComponent("messaging"),
                    List.of("classpath:db/moduvera-messaging/postgresql"),
                    true));
        };
    }
}
