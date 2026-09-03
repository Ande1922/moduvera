package io.github.ande1922.moduvera.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TenantPersistenceSchemaGateTest {

    private static final Pattern TENANT_COLUMN = Pattern.compile(
            "(?i)\\btenant_id\\s+(?:type\\s+)?(varchar|char|bigint|integer|numeric)(?:\\s*\\((\\d+)\\))?");

    private static final Map<String, Upgrade> LEGACY_UPGRADES = Map.of(
            "services/catalog/catalog-service/src/main/resources/db/migration/catalog/V1__create_catalog.sql",
            new Upgrade(
                    "services/catalog/catalog-service/src/main/resources/db/migration/catalog/V2__narrow_tenant_id.sql",
                    1),
            "services/catalog/catalog-service/src/main/resources/db/migration/catalog-mysql/V1__create_catalog.sql",
            new Upgrade(
                    "services/catalog/catalog-service/src/main/resources/db/migration/catalog-mysql/V2__narrow_tenant_id.sql",
                    1),
            "apps/identity-app/src/main/resources/db/migration/identity/V1__create_identity.sql",
            new Upgrade(
                    "apps/identity-app/src/main/resources/db/migration/identity/V2__narrow_tenant_id.sql",
                    3),
            "framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/main/resources/db/moduvera-messaging/postgresql/V1__create_moduvera_messaging.sql",
            new Upgrade(
                    "framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/main/resources/db/moduvera-messaging/postgresql/V2__narrow_tenant_id.sql",
                    2),
            "framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/main/resources/db/moduvera-messaging/mysql/V1__create_moduvera_messaging.sql",
            new Upgrade(
                    "framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/main/resources/db/moduvera-messaging/mysql/V2__narrow_tenant_id.sql",
                    2));

    @Test
    void effectiveProductAndVerificationTenantColumnsUseCanonicalStringContract() throws IOException {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();
        int[] definitions = {0};

        for (String sourceRoot : List.of("framework", "services", "apps", "examples", "verification")) {
            try (var files = Files.walk(root.resolve(sourceRoot))) {
                files.filter(Files::isRegularFile)
                        .filter(TenantPersistenceSchemaGateTest::isPersistenceAsset)
                        .forEach(file -> inspectDefinitions(root, file, violations, definitions));
            }
        }

        assertThat(definitions[0]).isGreaterThan(0);
        assertThat(violations)
                .as("non-canonical tenant persistence definitions")
                .isEmpty();

        for (var entry : LEGACY_UPGRADES.entrySet()) {
            assertSafeForwardMigration(root, entry.getKey(), entry.getValue());
        }
    }

    private static void inspectDefinitions(
            Path root, Path file, List<String> violations, int[] definitions) {
        String relative = normalized(root.relativize(file));
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot inspect " + file, exception);
        }
        var matcher = TENANT_COLUMN.matcher(content);
        while (matcher.find()) {
            definitions[0]++;
            String type = matcher.group(1);
            String length = matcher.group(2);
            boolean canonical = type.equalsIgnoreCase("varchar") && "64".equals(length);
            boolean preservedLegacy = LEGACY_UPGRADES.containsKey(relative)
                    && type.equalsIgnoreCase("varchar")
                    && "128".equals(length);
            if (!canonical && !preservedLegacy) {
                violations.add(relative + ": " + matcher.group());
            }
        }
    }

    private static void assertSafeForwardMigration(
            Path root, String legacyPath, Upgrade upgrade) throws IOException {
        String legacy = Files.readString(root.resolve(legacyPath));
        String migration = Files.readString(root.resolve(upgrade.path()));
        String normalizedMigration = migration.toUpperCase();

        assertThat(countMatches(TENANT_COLUMN, legacy))
                .as("legacy tenant column count in %s", legacyPath)
                .isEqualTo(upgrade.expectedColumns());
        assertThat(migration).contains("> 64");
        assertThat(migration).contains("tenant_id exceeds 64 characters");
        assertThat(normalizedMigration.indexOf("TENANT_ID EXCEEDS 64 CHARACTERS"))
                .isLessThan(normalizedMigration.indexOf("ALTER TABLE"));
        assertThat(countCanonicalTenantColumns(migration))
                .as("canonical tenant alterations in %s", upgrade.path())
                .isEqualTo(upgrade.expectedColumns());
        assertThat(normalizedMigration).doesNotContain("UPDATE ", "TRUNCATE ");
    }

    private static int countMatches(Pattern pattern, String content) {
        int count = 0;
        var matcher = pattern.matcher(content);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int countCanonicalTenantColumns(String content) {
        int count = 0;
        var matcher = TENANT_COLUMN.matcher(content);
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase("varchar") && "64".equals(matcher.group(2))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isPersistenceAsset(Path file) {
        String normalized = normalized(file);
        return !normalized.contains("/target/")
                && (normalized.endsWith(".sql")
                || (normalized.contains("/src/")
                        && (normalized.endsWith(".java") || normalized.endsWith(".xml"))));
    }

    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("maven.multiModuleProjectDirectory");
        Path candidate = Path.of(configured == null ? "." : configured)
                .toAbsolutePath()
                .normalize();
        while (candidate != null) {
            Path pom = candidate.resolve("pom.xml");
            try {
                if (Files.isRegularFile(pom)
                        && Files.readString(pom).contains("<module>apps/order-app</module>")) {
                    return candidate;
                }
            } catch (IOException exception) {
                throw new IllegalStateException("cannot inspect " + pom, exception);
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot locate the reactor root");
    }

    private record Upgrade(String path, int expectedColumns) {}
}
