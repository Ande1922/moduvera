package com.gaopc.platform.app.order;

import com.gaopc.platform.authorization.UseCaseAuthorizer;
import com.gaopc.platform.identifier.IdentifierGenerator;
import com.gaopc.platform.identifier.SnowflakeIdentifierGenerator;
import com.gaopc.platform.migration.DatabaseComponent;
import com.gaopc.platform.migration.DatabaseMigrator;
import com.gaopc.platform.migration.MigrationPlan;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OrderAppConfiguration {

    @Bean
    Clock orderClock() {
        return Clock.systemUTC();
    }

    @Bean
    IdentifierGenerator orderIdentifiers(@Value("${platform.identifier.worker-id:1}") long workerId) {
        return new SnowflakeIdentifierGenerator(workerId);
    }

    @Bean
    UseCaseAuthorizer orderAuthorizer() {
        return new UseCaseAuthorizer();
    }

    @Bean
    SmartInitializingSingleton orderMigrations(DataSource dataSource) {
        return () -> {
            var migrator = new DatabaseMigrator(dataSource);
            migrator.migrate(new MigrationPlan(
                    new DatabaseComponent("order"), List.of("classpath:db/migration/order"), true));
            migrator.migrate(new MigrationPlan(
                    new DatabaseComponent("messaging"),
                    List.of("classpath:db/platform-messaging/postgresql"),
                    true));
        };
    }

}
