package io.github.ande1922.moduvera.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;

class ExecutionContextTest {

    private static final Actor ACTOR = new Actor(ActorType.USER, "alice");
    private static final Initiator INITIATOR = new Initiator(ActorType.SERVICE, "gateway");

    @Test
    void exposesExplicitPlatformAndTenantScopes() {
        var platform = new ExecutionContext(
                ExecutionScope.platform(), ACTOR, INITIATOR, "corr-platform");
        var tenant = new ExecutionContext(
                ExecutionScope.tenant(new TenantId("tenant-a")), ACTOR, INITIATOR, "corr-tenant");

        assertThat(platform.scope()).isEqualTo(new ExecutionScope.Platform());
        assertThat(tenant.scope()).isEqualTo(new ExecutionScope.Tenant(new TenantId("tenant-a")));
        assertThat(tenant.requireTenantId()).isEqualTo(new TenantId("tenant-a"));
    }

    @Test
    void rejectsMissingScopeAndMissingTenantId() {
        assertThatThrownBy(() -> new ExecutionContext(
                        (ExecutionScope) null, ACTOR, INITIATOR, "corr-null-scope"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("scope");
        assertThatThrownBy(() -> ExecutionScope.tenant(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void requiresTenantForBothTheNewAndLegacyTenantAccessors() {
        var platform = ExecutionContext.initiatedBy(
                ExecutionScope.platform(), ACTOR, "corr-platform");

        assertThatThrownBy(platform::requireTenantId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tenant execution scope is required at this boundary");
        assertThatThrownBy(platform::tenantId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tenant execution scope is required at this boundary");
    }

    @Test
    void preservesTenantOnlyConstructionAndInitiatorConvenienceCalls() {
        var constructed = new ExecutionContext(
                new TenantId("tenant-a"), ACTOR, INITIATOR, "corr-constructed");
        var initiated = ExecutionContext.initiatedBy(
                new TenantId("tenant-a"), ACTOR, "corr-initiated");

        assertThat(constructed.scope()).isEqualTo(ExecutionScope.tenant(new TenantId("tenant-a")));
        assertThat(constructed.tenantId()).isEqualTo(new TenantId("tenant-a"));
        assertThat(initiated.initiator()).isEqualTo(Initiator.from(ACTOR));
        assertThat(initiated.tenantId()).isEqualTo(new TenantId("tenant-a"));
    }

    @Test
    void publishesTheExplicitScopeAsTheRecordComponent() {
        assertThat(ExecutionContext.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("scope", "actor", "initiator", "correlationId");
    }
}
