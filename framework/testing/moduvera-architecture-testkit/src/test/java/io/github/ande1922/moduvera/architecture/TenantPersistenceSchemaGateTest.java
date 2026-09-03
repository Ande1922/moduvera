package io.github.ande1922.moduvera.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TenantPersistenceSchemaGateTest {

    private static final String TENANT_IDENTIFIER =
            "(?:tenant_id|\"tenant_id\"|`tenant_id`|\\[tenant_id\\])";
    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?is)\\bCREATE\\s+TABLE\\b.*?;");
    private static final Pattern CREATE_TABLE_TENANT_COLUMN = Pattern.compile(
            "(?is)(?:\\(|,)\\s*" + TENANT_IDENTIFIER
                    + "\\s+([a-z][a-z0-9_]*)(?:\\s*\\(\\s*(\\d+)\\s*\\))?");
    private static final Pattern ALTER_TABLE =
            Pattern.compile("(?is)\\bALTER\\s+TABLE\\b.*?;");
    private static final Pattern ALTER_TABLE_TENANT_COLUMN = Pattern.compile(
            "(?is)\\b(?:ALTER\\s+COLUMN|MODIFY(?:\\s+COLUMN)?|ADD(?:\\s+COLUMN)?)\\s+"
                    + TENANT_IDENTIFIER
                    + "\\s+(?:TYPE\\s+)?([a-z][a-z0-9_]*)(?:\\s*\\(\\s*(\\d+)\\s*\\))?");

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

    @Test
    void rejectsTenantColumnMutationsRegardlessOfTypeOrIdentifierQuoting() {
        for (String declaration : List.of(
                "tenant_id TEXT NOT NULL",
                "tenant_id BIGINT NOT NULL",
                "\"tenant_id\" VARCHAR(128) NOT NULL",
                "`tenant_id` VARCHAR(128) NOT NULL",
                "[tenant_id] VARCHAR(128) NOT NULL")) {
            List<String> violations = new ArrayList<>();
            inspectDefinitions(
                    "mutation.sql",
                    "CREATE TABLE mutation (id BIGINT, " + declaration + ");",
                    violations,
                    ignored -> {});

            assertThat(violations).as(declaration).hasSize(1);
        }
    }

    @Test
    void ignoresTenantReferencesAndSqlTextThatAreNotColumnDeclarations() {
        String content = """
                -- tenant_id TEXT is documentation, not DDL
                /* CREATE TABLE ignored (tenant_id BIGINT); */
                SELECT tenant_id FROM tenant_record;
                SELECT id, tenant_id FROM tenant_record;
                INSERT INTO tenant_record (tenant_id, value) VALUES ('tenant_id TEXT', 'x');
                CREATE INDEX tenant_lookup ON tenant_record (tenant_id);
                """;
        List<String> violations = new ArrayList<>();
        int[] definitions = {0};

        inspectDefinitions("references.sql", content, violations, ignored -> definitions[0]++);

        assertThat(definitions[0]).isZero();
        assertThat(violations).isEmpty();
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
        inspectDefinitions(relative, content, violations, ignored -> definitions[0]++);
    }

    private static void inspectDefinitions(
            String relative,
            String content,
            List<String> violations,
            Consumer<TenantColumnDefinition> definitionConsumer) {
        String inspectable = stripCommentsAndSqlStrings(content);
        inspectStatements(
                CREATE_TABLE.matcher(inspectable),
                CREATE_TABLE_TENANT_COLUMN,
                relative,
                violations,
                definitionConsumer);
        inspectStatements(
                ALTER_TABLE.matcher(inspectable),
                ALTER_TABLE_TENANT_COLUMN,
                relative,
                violations,
                definitionConsumer);
    }

    private static void inspectStatements(
            Matcher statements,
            Pattern tenantColumns,
            String relative,
            List<String> violations,
            Consumer<TenantColumnDefinition> definitionConsumer) {
        while (statements.find()) {
            Matcher columns = tenantColumns.matcher(statements.group());
            while (columns.find()) {
                TenantColumnDefinition definition = new TenantColumnDefinition(
                        columns.group(1).toUpperCase(Locale.ROOT), columns.group(2));
                definitionConsumer.accept(definition);
                boolean canonical = definition.isVarchar(64);
                boolean preservedLegacy = LEGACY_UPGRADES.containsKey(relative)
                        && definition.isVarchar(128);
                if (!canonical && !preservedLegacy) {
                    violations.add(relative + ": " + columns.group());
                }
            }
        }
    }

    private static void assertSafeForwardMigration(
            Path root, String legacyPath, Upgrade upgrade) throws IOException {
        String legacy = Files.readString(root.resolve(legacyPath));
        String migration = Files.readString(root.resolve(upgrade.path()));
        String normalizedMigration = migration.toUpperCase();

        assertThat(findTenantColumns(legacy))
                .as("legacy tenant column count in %s", legacyPath)
                .hasSize(upgrade.expectedColumns())
                .allMatch(definition -> definition.isVarchar(128));
        assertThat(migration).contains("> 64");
        assertThat(migration).contains("tenant_id exceeds 64 characters");
        assertThat(normalizedMigration.indexOf("TENANT_ID EXCEEDS 64 CHARACTERS"))
                .isLessThan(normalizedMigration.indexOf("ALTER TABLE"));
        assertThat(findTenantColumns(migration))
                .as("canonical tenant alterations in %s", upgrade.path())
                .hasSize(upgrade.expectedColumns())
                .allMatch(definition -> definition.isVarchar(64));
        assertThat(normalizedMigration).doesNotContain("UPDATE ", "TRUNCATE ");
    }

    private static List<TenantColumnDefinition> findTenantColumns(String content) {
        List<TenantColumnDefinition> definitions = new ArrayList<>();
        inspectDefinitions("migration.sql", content, new ArrayList<>(), definitions::add);
        return definitions;
    }

    private static String stripCommentsAndSqlStrings(String content) {
        StringBuilder sanitized = new StringBuilder(content.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean singleQuoted = false;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            char next = index + 1 < content.length() ? content.charAt(index + 1) : '\0';
            if (lineComment) {
                lineComment = current != '\n';
                sanitized.append(current == '\n' ? '\n' : ' ');
            } else if (blockComment) {
                if (current == '*' && next == '/') {
                    sanitized.append("  ");
                    blockComment = false;
                    index++;
                } else {
                    sanitized.append(current == '\n' ? '\n' : ' ');
                }
            } else if (singleQuoted) {
                if (current == '\'' && next == '\'') {
                    sanitized.append("  ");
                    index++;
                } else {
                    sanitized.append(current == '\n' ? '\n' : ' ');
                    singleQuoted = current != '\'';
                }
            } else if ((current == '-' && next == '-') || (current == '/' && next == '/')) {
                sanitized.append("  ");
                lineComment = true;
                index++;
            } else if (current == '/' && next == '*') {
                sanitized.append("  ");
                blockComment = true;
                index++;
            } else if (current == '\'') {
                sanitized.append(' ');
                singleQuoted = true;
            } else {
                sanitized.append(current);
            }
        }
        return sanitized.toString();
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

    private record TenantColumnDefinition(String type, String length) {

        private boolean isVarchar(int expectedLength) {
            return type.equals("VARCHAR") && Integer.toString(expectedLength).equals(length);
        }
    }
}
