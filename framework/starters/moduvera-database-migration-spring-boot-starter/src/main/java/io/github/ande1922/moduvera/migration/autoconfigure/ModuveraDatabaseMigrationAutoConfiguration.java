package io.github.ande1922.moduvera.migration.autoconfigure;

import io.github.ande1922.moduvera.migration.MigrationDefinition;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ModuveraDatabaseMigrationProperties.class)
public class ModuveraDatabaseMigrationAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "moduvera.database.migration",
            name = "mode",
            havingValue = "startup")
    StartupMigrationRunner moduveraStartupMigrationRunner(
            List<MigrationDefinition> definitions,
            DataSource dataSource,
            ModuveraDatabaseMigrationProperties properties) {
        return new StartupMigrationRunner(definitions, dataSource, properties.isInitialize());
    }
}
