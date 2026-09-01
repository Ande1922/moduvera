package io.github.ande1922.moduvera.context;

import java.util.Objects;
import java.util.regex.Pattern;

public record ExecutionContext(TenantId tenantId, Actor actor, Initiator initiator, String correlationId) {

    private static final Pattern CORRELATION_FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public ExecutionContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(initiator, "initiator");
        Objects.requireNonNull(correlationId, "correlationId");
        if (!isValidCorrelationId(correlationId)) {
            throw new IllegalArgumentException("correlationId must be 1-128 portable identifier characters");
        }
    }

    public static ExecutionContext initiatedBy(TenantId tenantId, Actor actor, String correlationId) {
        return new ExecutionContext(tenantId, actor, Initiator.from(actor), correlationId);
    }

    public static boolean isValidCorrelationId(String candidate) {
        return candidate != null && CORRELATION_FORMAT.matcher(candidate).matches();
    }
}
