package io.github.ande1922.moduvera.context;

import java.util.Objects;

/** The trusted resource scope of one execution; it does not grant authorization. */
public sealed interface ExecutionScope permits ExecutionScope.Platform, ExecutionScope.Tenant {

    /** Creates a scope that is not bound to a tenant. */
    static ExecutionScope platform() {
        return new Platform();
    }

    /** Creates a scope bound to one valid tenant. */
    static ExecutionScope tenant(TenantId tenantId) {
        return new Tenant(tenantId);
    }

    /** An execution that is not bound to a tenant. */
    record Platform() implements ExecutionScope {}

    /** An execution bound to exactly one tenant. */
    record Tenant(TenantId tenantId) implements ExecutionScope {

        public Tenant {
            Objects.requireNonNull(tenantId, "tenantId");
        }
    }
}
