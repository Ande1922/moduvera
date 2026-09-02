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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ModuveraBomConsumerSmokeTest {

    private static final String WITHDRAWN_OBJECT_STORAGE_ARTIFACT = "moduvera-object-storage-api";

    @Test
    void importsVersionlessPlatformContractsFromThePublishedBom() {
        var page = PageRequest.of(0, 20);
        var messageType = new MessageType("inventory.reserved.v1");
        var permission = new PermissionCode("notes:read");
        var tenantId = new TenantId("tenant-a");
        var errorCode = new ErrorCode("notes.not-found");
        var lockKey = LockKey.tenant(tenantId, "note", "42");
        var localLocks = new LocalLockProvider();
        var job = new JobDefinition("notes-cleanup", JobDefinition.Scope.TENANT, Duration.ofSeconds(1));
        IdentifierGenerator identifiers = () -> 42L;

        assertThat(page.size()).isEqualTo(20);
        assertThat(messageType.value()).isEqualTo("inventory.reserved.v1");
        assertThat(permission.value()).isEqualTo("notes:read");
        assertThat(tenantId.value()).isEqualTo("tenant-a");
        assertThat(errorCode.value()).isEqualTo("notes.not-found");
        assertThat(lockKey.value()).isEqualTo("tenant:tenant-a:note:42");
        assertThat(localLocks).isNotNull();
        assertThat(job.name()).isEqualTo("notes-cleanup");
        assertThat(identifiers.nextId()).isEqualTo(42L);
        assertThat(ModuveraDatabaseMigrationMode.DISABLED.name()).isEqualTo("DISABLED");
    }

    @Test
    void excludesTheWithdrawnObjectStorageContractFromThePublishedSurface() throws IOException {
        Path repository = repositoryRoot();

        assertThat(Files.readString(repository.resolve("pom.xml")))
                .doesNotContain(WITHDRAWN_OBJECT_STORAGE_ARTIFACT);
        assertThat(Files.readString(repository.resolve("framework/bom/pom.xml")))
                .doesNotContain(WITHDRAWN_OBJECT_STORAGE_ARTIFACT);
        assertThat(repository.resolve("framework/foundation/moduvera-object-storage-api/pom.xml"))
                .doesNotExist();
        assertThatThrownBy(() -> Class.forName("io.github.ande1922.moduvera.storage.ObjectStorage"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("framework/bom/pom.xml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("could not locate repository root");
        }
        return current;
    }
}
