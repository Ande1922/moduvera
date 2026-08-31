package io.github.ande1922.moduvera.reference.app.order;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.identifier.IdentifierGenerator;
import io.github.ande1922.moduvera.identifier.SnowflakeIdentifierGenerator;
import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationPlan;
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
    IdentifierGenerator orderIdentifiers(@Value("${moduvera.identifier.worker-id:1}") long workerId) {
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
                    List.of("classpath:db/moduvera-messaging/postgresql"),
                    true));
        };
    }

}
