package io.github.ande1922.moduvera.migration;

import java.util.Objects;
import java.util.regex.Pattern;

public record DatabaseComponent(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_]{0,47}");

    public DatabaseComponent {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("database component must use lowercase identifier characters");
        }
    }

    public String historyTable() {
        return "flyway_history_" + value;
    }
}
