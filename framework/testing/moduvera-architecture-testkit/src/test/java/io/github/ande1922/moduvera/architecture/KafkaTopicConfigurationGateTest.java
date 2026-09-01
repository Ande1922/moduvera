package io.github.ande1922.moduvera.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ande1922.moduvera.messaging.kafka.KafkaTopicName;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class KafkaTopicConfigurationGateTest {

    private static final String DESTINATION_PREFIX = "spring.cloud.stream.bindings.";
    private static final String DESTINATION_SUFFIX = ".destination";
    private static final String DLQ_PREFIX = "spring.cloud.stream.kafka.bindings.";
    private static final String DLQ_SUFFIX = ".consumer.dlq-name";

    @Test
    void applicationKafkaTopicsUseTheCanonicalPhysicalNamingPolicy() throws IOException {
        Path repository = repositoryRoot();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(repository)) {
            files.filter(Files::isRegularFile)
                    .filter(KafkaTopicConfigurationGateTest::isApplicationConfiguration)
                    .sorted()
                    .forEach(file -> inspect(repository, file, violations));
        }
        assertTrue(
                violations.isEmpty(),
                () -> "Invalid Kafka topic configuration:\n" + String.join("\n", violations));
    }

    private static void inspect(Path repository, Path file, List<String> violations) {
        try {
            if (file.getFileName().toString().endsWith(".properties")) {
                inspectProperties(repository, file, violations);
            } else {
                inspectYaml(repository, file, violations);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot inspect " + file, exception);
        }
    }

    private static void inspectProperties(
            Path repository, Path file, List<String> violations) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        properties.stringPropertyNames().stream()
                .filter(KafkaTopicConfigurationGateTest::isTopicProperty)
                .forEach(key -> validate(repository, file, key, properties.getProperty(key), violations));
    }

    private static void inspectYaml(
            Path repository, Path file, List<String> violations) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            for (Object document : new Yaml().loadAll(input)) {
                inspectBindingMap(
                        repository,
                        file,
                        mapAt(document, "spring", "cloud", "stream", "bindings"),
                        "destination",
                        violations);
                inspectKafkaBindings(
                        repository,
                        file,
                        mapAt(document, "spring", "cloud", "stream", "kafka", "bindings"),
                        violations);
            }
        }
    }

    private static void inspectBindingMap(
            Path repository,
            Path file,
            Map<?, ?> bindings,
            String property,
            List<String> violations) {
        bindings.forEach((bindingName, configuration) -> {
            if (configuration instanceof Map<?, ?> values && values.get(property) instanceof String topic) {
                validate(repository, file, bindingName + "." + property, topic, violations);
            }
        });
    }

    private static void inspectKafkaBindings(
            Path repository, Path file, Map<?, ?> bindings, List<String> violations) {
        bindings.forEach((bindingName, configuration) -> {
            if (configuration instanceof Map<?, ?> values
                    && values.get("consumer") instanceof Map<?, ?> consumer
                    && consumer.get("dlq-name") instanceof String topic) {
                validate(repository, file, bindingName + ".consumer.dlq-name", topic, violations);
            }
        });
    }

    private static void validate(
            Path repository,
            Path file,
            Object property,
            String configuredValue,
            List<String> violations) {
        String topic = defaultValue(configuredValue);
        if (topic == null) {
            return;
        }
        try {
            KafkaTopicName.requireValid(topic, property.toString());
        } catch (IllegalStateException exception) {
            violations.add(repository.relativize(file) + ": " + exception.getMessage());
        }
    }

    private static String defaultValue(String configuredValue) {
        if (!configuredValue.startsWith("${")) {
            return configuredValue;
        }
        int separator = configuredValue.indexOf(':');
        return separator < 0 ? null : configuredValue.substring(separator + 1, configuredValue.length() - 1);
    }

    private static boolean isTopicProperty(String key) {
        return (key.startsWith(DESTINATION_PREFIX) && key.endsWith(DESTINATION_SUFFIX))
                || (key.startsWith(DLQ_PREFIX) && key.endsWith(DLQ_SUFFIX));
    }

    private static Map<?, ?> mapAt(Object root, String... path) {
        Object current = root;
        for (String segment : path) {
            if (!(current instanceof Map<?, ?> values)) {
                return Map.of();
            }
            current = values.get(segment);
        }
        return current instanceof Map<?, ?> values ? values : Map.of();
    }

    private static boolean isApplicationConfiguration(Path file) {
        String normalized = file.toString().replace('\\', '/');
        String name = file.getFileName().toString();
        return normalized.contains("/src/main/resources/")
                && name.startsWith("application")
                && (name.endsWith(".yml")
                        || name.endsWith(".yaml")
                        || name.endsWith(".properties"));
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
}
