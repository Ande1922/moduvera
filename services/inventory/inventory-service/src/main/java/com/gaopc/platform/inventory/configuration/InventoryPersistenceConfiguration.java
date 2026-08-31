package com.gaopc.platform.inventory.configuration;

import com.gaopc.platform.inventory.application.InventoryResultPublisher;
import com.gaopc.platform.inventory.domain.InventoryStore;
import com.gaopc.platform.inventory.infrastructure.messaging.OutboxInventoryResultPublisher;
import com.gaopc.platform.inventory.infrastructure.persistence.InventoryMapper;
import com.gaopc.platform.inventory.infrastructure.persistence.MybatisInventoryStore;
import com.gaopc.platform.message.publication.DurablePublication;
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
                    List.of("classpath:db/platform-messaging/postgresql"),
                    true));
        };
    }
}
