package io.github.ande1922.moduvera.testing.bom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.api.PageRequest;
import io.github.ande1922.moduvera.authorization.PermissionCode;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.error.ErrorCode;
import io.github.ande1922.moduvera.identifier.IdentifierGenerator;
import io.github.ande1922.moduvera.lock.LockKey;
import io.github.ande1922.moduvera.lock.local.LocalLockProvider;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.migration.autoconfigure.ModuveraDatabaseMigrationMode;
import io.github.ande1922.moduvera.scheduler.JobDefinition;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModuveraBomConsumerSmokeTest {

    private static final String WITHDRAWN_OBJECT_STORAGE_ARTIFACT = "moduvera-object-storage-api";
    private static final List<String> PUBLISHED_ARTIFACTS =
            List.of(
                    "moduvera-kernel",
                    "moduvera-database-migration",
                    "moduvera-lock-core",
                    "moduvera-lock-local",
                    "moduvera-message-core",
                    "moduvera-auth-resource-server-autoconfigure",
                    "moduvera-data-mybatis-plus-spring-boot-starter",
                    "moduvera-database-migration-spring-boot-starter",
                    "moduvera-messaging-kafka-spring-boot-starter",
                    "moduvera-scheduler-spring-boot-starter",
                    "moduvera-test-support",
                    "moduvera-architecture-testkit",
                    "moduvera-web-spring-boot-starter",
                    "catalog-api",
                    "catalog-service",
                    "inventory-api",
                    "inventory-service",
                    "order-api",
                    "order-service");

    @Test
    void importsVersionlessPlatformContractsFromThePublishedBom() {
        var page = PageRequest.of(0, 20);
        var messageType = new MessageType("inventory.reserved.v1");
        var permission = new PermissionCode("notes:read");
        var tenantId = new TenantId("tenant-a");
        var errorCode = new ErrorCode("notes.not-found");
        IdentifierGenerator identifiers = () -> 42L;
        Class<?>[] frozenCapabilityTypes = {LockKey.class, LocalLockProvider.class, JobDefinition.class};

        assertThat(page.size()).isEqualTo(20);
        assertThat(messageType.value()).isEqualTo("inventory.reserved.v1");
        assertThat(permission.value()).isEqualTo("notes:read");
        assertThat(tenantId.value()).isEqualTo("tenant-a");
        assertThat(errorCode.value()).isEqualTo("notes.not-found");
        assertThat(frozenCapabilityTypes).doesNotContainNull();
        assertThat(identifiers.nextId()).isEqualTo(42L);
        assertThat(ModuveraDatabaseMigrationMode.DISABLED.name()).isEqualTo("DISABLED");
        assertThat(W3CTraceContextPropagator.getInstance().fields()).containsExactly("traceparent", "tracestate");
    }

    @Test
    void excludesTheWithdrawnCoordinateFromEffectiveDependencyManagement() throws IOException {
        var effectivePomResource =
                ModuveraBomConsumerSmokeTest.class.getResource("/moduvera-bom-effective-pom.xml");
        assertThat(effectivePomResource)
                .as("consumer effective POM generated from the imported Moduvera BOM")
                .isNotNull();

        String effectivePom;
        try (var input = effectivePomResource.openStream()) {
            effectivePom = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        int managementStart = effectivePom.indexOf("<dependencyManagement>");
        int managementEnd = effectivePom.indexOf("</dependencyManagement>");
        assertThat(managementStart).isGreaterThanOrEqualTo(0);
        assertThat(managementEnd).isGreaterThan(managementStart);

        String effectiveDependencyManagement =
                effectivePom.substring(managementStart, managementEnd);
        for (String artifactId : PUBLISHED_ARTIFACTS) {
            assertThat(effectiveDependencyManagement)
                    .contains("<artifactId>" + artifactId + "</artifactId>");
        }
        assertThat(effectiveDependencyManagement)
                .doesNotContain("<artifactId>" + WITHDRAWN_OBJECT_STORAGE_ARTIFACT + "</artifactId>");
        assertThatThrownBy(() -> Class.forName("io.github.ande1922.moduvera.storage.ObjectStorage"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
