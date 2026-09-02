package io.github.ande1922.moduvera.example.notes.migration;

import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class NotesMigrationConfiguration {

    @Bean
    MigrationDefinition notesMigrationDefinition() {
        return new MigrationDefinition(
                new DatabaseComponent("notes_demo"),
                List.of("classpath:db/migration/notes"),
                List.of(),
                Map.of());
    }
}
