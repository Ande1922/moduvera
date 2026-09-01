package io.github.ande1922.moduvera.migration;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record MigrationDefinition(
        DatabaseComponent component,
        List<String> postgresqlLocations,
        List<String> mysqlLocations,
        Map<String, String> placeholders) {

    public MigrationDefinition {
        Objects.requireNonNull(component, "component");
        postgresqlLocations = List.copyOf(postgresqlLocations);
        mysqlLocations = List.copyOf(mysqlLocations);
        placeholders = Map.copyOf(placeholders);
        requireLocations("postgresql", postgresqlLocations);
        requireLocations("mysql", mysqlLocations);
    }

    private static void requireLocations(String dialect, List<String> locations) {
        if (locations.isEmpty() || locations.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(dialect + " migration locations must be non-blank");
        }
        if (locations.stream().distinct().count() != locations.size()) {
            throw new IllegalArgumentException(dialect + " migration locations contain duplicates");
        }
    }
}
