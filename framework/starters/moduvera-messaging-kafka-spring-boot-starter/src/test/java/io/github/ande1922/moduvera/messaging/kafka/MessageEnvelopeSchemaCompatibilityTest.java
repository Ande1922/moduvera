package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MessageEnvelopeSchemaCompatibilityTest {

    private static final String SCHEMA_ROOT = "META-INF/moduvera-message-schemas/";
    private static final String FIXTURE_ROOT = "messaging/compatibility/v1/";
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentSchemaCases")
    void currentSchemasAcceptFixedLegacyAndCreationFixtures(SchemaCase schemaCase) throws IOException {
        Schema schema = schema(SCHEMA_ROOT + schemaCase.schema());

        assertThat(schema.validate(resourceText(FIXTURE_ROOT + schemaCase.fixture()), InputFormat.JSON))
                .as(schemaCase.toString())
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("newCreationFixtures")
    void currentSchemasEnforceTheCreationCarrierSizeBoundary(SchemaCase schemaCase) throws IOException {
        Schema schema = schema(SCHEMA_ROOT + schemaCase.schema());
        String oversized = resourceText(FIXTURE_ROOT + schemaCase.fixture())
                .replace(
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                        "x".repeat(513));

        assertThat(schema.validate(oversized, InputFormat.JSON)).isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("newCommandFixture")
    void oldStrictCommandSchemaRejectsTheNewCreationExtension(SchemaCase schemaCase) throws IOException {
        Schema oldStrictSchema = schema(FIXTURE_ROOT + schemaCase.schema());

        assertThat(oldStrictSchema.validate(
                        resourceText(FIXTURE_ROOT + schemaCase.fixture()), InputFormat.JSON))
                .extracting(com.networknt.schema.Error::getKeyword)
                .contains("additionalProperties");
    }

    private static List<SchemaCase> currentSchemaCases() {
        return List.of(
                new SchemaCase("structured-event-envelope.schema.json", "event-without-creation.json"),
                new SchemaCase("structured-event-envelope.schema.json", "event-with-creation.json"),
                new SchemaCase("async-command-envelope.schema.json", "command-without-creation.json"),
                new SchemaCase("async-command-envelope.schema.json", "command-with-creation.json"));
    }

    private static List<SchemaCase> newCreationFixtures() {
        return List.of(
                new SchemaCase("structured-event-envelope.schema.json", "event-with-creation.json"),
                new SchemaCase("async-command-envelope.schema.json", "command-with-creation.json"));
    }

    private static List<SchemaCase> newCommandFixture() {
        return List.of(new SchemaCase(
                "async-command-envelope-before-creation.schema.json", "command-with-creation.json"));
    }

    private static Schema schema(String resource) throws IOException {
        return SCHEMA_REGISTRY.getSchema(resourceText(resource));
    }

    private static String resourceText(String resource) throws IOException {
        try (var input = MessageEnvelopeSchemaCompatibilityTest.class
                .getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException("missing test resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record SchemaCase(String schema, String fixture) {}
}
