package io.github.ande1922.moduvera.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    @ParameterizedTest
    @MethodSource("singleDialectDefinitions")
    void supportsDefinitionsForASingleDialect(
            List<String> postgresqlLocations, List<String> mysqlLocations) {
        var definition = new MigrationDefinition(
                new DatabaseComponent("catalog"), postgresqlLocations, mysqlLocations, Map.of());

        assertThat(definition.postgresqlLocations()).isEqualTo(postgresqlLocations);
        assertThat(definition.mysqlLocations()).isEqualTo(mysqlLocations);
    }

    @Test
    void rejectsDefinitionsThatSupportNoDialect() {
        assertThatThrownBy(() -> new MigrationDefinition(
                        new DatabaseComponent("catalog"), List.of(), List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one migration dialect");
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("duplicateLocations")
    void rejectsDuplicateResourceLocationsWithinEachDialect(
            String dialect, List<String> postgresqlLocations, List<String> mysqlLocations) {
        assertThatThrownBy(() -> new MigrationDefinition(
                        new DatabaseComponent("catalog"),
                        postgresqlLocations,
                        mysqlLocations,
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(dialect)
                .hasMessageContaining("duplicate");
    }

    static Stream<Arguments> duplicateLocations() {
        var postgresql = "classpath:db/catalog/postgresql";
        var mysql = "classpath:db/catalog/mysql";
        return Stream.of(
                Arguments.of("postgresql", List.of(postgresql, postgresql), List.of(mysql)),
                Arguments.of("mysql", List.of(postgresql), List.of(mysql, mysql)));
    }

    static Stream<Arguments> singleDialectDefinitions() {
        return Stream.of(
                Arguments.of(List.of("classpath:db/catalog/postgresql"), List.of()),
                Arguments.of(List.of(), List.of("classpath:db/catalog/mysql")));
    }
}
