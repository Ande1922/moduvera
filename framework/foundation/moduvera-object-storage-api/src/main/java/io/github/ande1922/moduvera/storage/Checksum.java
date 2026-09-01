package io.github.ande1922.moduvera.storage;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record Checksum(Algorithm algorithm, String value) {

    private static final Pattern HEX = Pattern.compile("[0-9a-f]+");

    public Checksum {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(value, "value");
        value = value.toLowerCase(Locale.ROOT);
        int expectedLength = algorithm == Algorithm.SHA_256 ? 64 : 32;
        if (value.length() != expectedLength || !HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("checksum is not valid lowercase hexadecimal for " + algorithm);
        }
    }

    public enum Algorithm {
        MD5,
        SHA_256
    }
}
