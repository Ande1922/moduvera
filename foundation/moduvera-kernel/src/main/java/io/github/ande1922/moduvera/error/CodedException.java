package io.github.ande1922.moduvera.error;

import java.util.Objects;

public class CodedException extends RuntimeException {

    private final ErrorCode code;

    public CodedException(ErrorCode code, String safeDetail) {
        super(requireDetail(safeDetail));
        this.code = Objects.requireNonNull(code, "code");
    }

    public CodedException(ErrorCode code, String safeDetail, Throwable cause) {
        super(requireDetail(safeDetail), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public final ErrorCode code() {
        return code;
    }

    private static String requireDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("safeDetail must not be blank");
        }
        return detail;
    }
}
