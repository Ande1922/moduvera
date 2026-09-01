package io.github.ande1922.moduvera.testing.bom;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.api.PageRequest;
import io.github.ande1922.moduvera.authorization.PermissionCode;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.error.ErrorCode;
import io.github.ande1922.moduvera.identifier.IdentifierGenerator;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.migration.autoconfigure.ModuveraDatabaseMigrationMode;
import org.junit.jupiter.api.Test;

class ModuveraBomConsumerSmokeTest {

    @Test
    void importsVersionlessPlatformContractsFromThePublishedBom() {
        var page = PageRequest.of(0, 20);
        var messageType = new MessageType("inventory.reserved.v1");
        var permission = new PermissionCode("notes:read");
        var tenantId = new TenantId("tenant-a");
        var errorCode = new ErrorCode("notes.not-found");
        IdentifierGenerator identifiers = () -> 42L;

        assertThat(page.size()).isEqualTo(20);
        assertThat(messageType.value()).isEqualTo("inventory.reserved.v1");
        assertThat(permission.value()).isEqualTo("notes:read");
        assertThat(tenantId.value()).isEqualTo("tenant-a");
        assertThat(errorCode.value()).isEqualTo("notes.not-found");
        assertThat(identifiers.nextId()).isEqualTo(42L);
        assertThat(ModuveraDatabaseMigrationMode.DISABLED.name()).isEqualTo("DISABLED");
    }
}
