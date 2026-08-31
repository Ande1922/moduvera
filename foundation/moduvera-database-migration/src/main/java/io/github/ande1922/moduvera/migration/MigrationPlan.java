package io.github.ande1922.moduvera.migration;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record MigrationPlan(
        DatabaseComponent component,
        List<String> locations,
        Map<String, String> placeholders,
        boolean initialize) {

    public MigrationPlan {
        Objects.requireNonNull(component, "component");
        locations = List.copyOf(locations);
        placeholders = Map.copyOf(placeholders);
        if (locations.isEmpty() || locations.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("at least one non-blank migration location is required");
        }
    }

    public MigrationPlan(DatabaseComponent component, List<String> locations, boolean initialize) {
        this(component, locations, Map.of(), initialize);
    }
}
