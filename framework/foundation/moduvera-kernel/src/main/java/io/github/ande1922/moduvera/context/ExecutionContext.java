package io.github.ande1922.moduvera.context;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable trusted identity and resource scope for one logical execution. */
public record ExecutionContext(ExecutionScope scope, Actor actor, Initiator initiator, String correlationId) {

    private static final Pattern CORRELATION_FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public ExecutionContext {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(initiator, "initiator");
        Objects.requireNonNull(correlationId, "correlationId");
        if (!isValidCorrelationId(correlationId)) {
            throw new IllegalArgumentException("correlationId must be 1-128 portable identifier characters");
        }
    }

    /**
     * Compatibility constructor for existing tenant-only callers. New callers may construct an
     * explicit {@link ExecutionScope} with the canonical record constructor.
     */
    public ExecutionContext(TenantId tenantId, Actor actor, Initiator initiator, String correlationId) {
        this(ExecutionScope.tenant(tenantId), actor, initiator, correlationId);
    }

    public static ExecutionContext initiatedBy(TenantId tenantId, Actor actor, String correlationId) {
        return new ExecutionContext(tenantId, actor, Initiator.from(actor), correlationId);
    }

    public static ExecutionContext initiatedBy(ExecutionScope scope, Actor actor, String correlationId) {
        return new ExecutionContext(scope, actor, Initiator.from(actor), correlationId);
    }

    /** Returns the tenant or fails when this is a Platform execution. */
    public TenantId requireTenantId() {
        if (scope instanceof ExecutionScope.Tenant tenant) {
            return tenant.tenantId();
        }
        throw new IllegalStateException("tenant execution scope is required at this boundary");
    }

    /** Compatibility alias for the former tenant record accessor. */
    public TenantId tenantId() {
        return requireTenantId();
    }

    public static boolean isValidCorrelationId(String candidate) {
        return candidate != null && CORRELATION_FORMAT.matcher(candidate).matches();
    }
}
