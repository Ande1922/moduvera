package io.github.ande1922.moduvera.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationDefinitionTest {

    @Test
    void exposesServiceOwnedMigrationMetadataWithoutRuntimeDependencies() {
        var definition = new MigrationDefinition(
                new DatabaseComponent("catalog"),
                List.of("classpath:db/catalog/postgresql"),
                List.of("classpath:db/catalog/mysql"),
                Map.of("catalogSchema", "catalog"));

        assertThat(definition.component()).isEqualTo(new DatabaseComponent("catalog"));
        assertThat(definition.postgresqlLocations()).containsExactly("classpath:db/catalog/postgresql");
        assertThat(definition.mysqlLocations()).containsExactly("classpath:db/catalog/mysql");
        assertThat(definition.placeholders()).containsEntry("catalogSchema", "catalog");
    }

    @Test
    void rejectsDefinitionsMissingAMysqlResource() {
        assertThatThrownBy(() -> new MigrationDefinition(
                        new DatabaseComponent("catalog"),
                        List.of("classpath:db/catalog/postgresql"),
                        List.of(),
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mysql");
    }

    @Test
    void rejectsDefinitionsMissingAPostgresqlResource() {
        assertThatThrownBy(() -> new MigrationDefinition(
                        new DatabaseComponent("catalog"),
                        List.of(),
                        List.of("classpath:db/catalog/mysql"),
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postgresql");
    }

    @Test
    void rejectsDefinitionsWithBlankResourceLocations() {
        assertThatThrownBy(() -> new MigrationDefinition(
                        new DatabaseComponent("catalog"),
                        List.of(" "),
                        List.of("classpath:db/catalog/mysql"),
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postgresql");
    }
}
