package io.github.ande1922.moduvera.messaging.kafka.autoconfigure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public final class KafkaOutboxEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String KEY_SERIALIZER =
            "org.apache.kafka.common.serialization.StringSerializer";
    private static final String VALUE_SERIALIZER =
            "org.apache.kafka.common.serialization.ByteArraySerializer";

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, String> routes = Binder.get(environment)
                .bind(
                        "moduvera.messaging.kafka.routes",
                        Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        List<String> consumers = Binder.get(environment)
                .bind(
                        "moduvera.messaging.kafka.consumer-bindings",
                        Bindable.listOf(String.class))
                .orElse(List.of());
        if (routes.isEmpty() && consumers.isEmpty()) {
            return;
        }
        long acknowledgementTimeout = Binder.get(environment)
                .bind("moduvera.messaging.kafka.broker-ack-timeout", java.time.Duration.class)
                .orElse(java.time.Duration.ofSeconds(2))
                .toMillis();
        Map<String, Object> defaults = new LinkedHashMap<>();
        routes.values().forEach(bindingName -> {
            defaults.put(
                    "spring.cloud.stream.bindings." + bindingName + ".producer.use-native-encoding",
                    true);
            String producerPrefix =
                    "spring.cloud.stream.kafka.bindings." + bindingName + ".producer.";
            defaults.put(producerPrefix + "sync", true);
            defaults.put(producerPrefix + "configuration.acks", "all");
            defaults.put(producerPrefix + "configuration.delivery.timeout.ms", acknowledgementTimeout);
            defaults.put(producerPrefix + "configuration.request.timeout.ms", acknowledgementTimeout);
            defaults.put(producerPrefix + "configuration.max.block.ms", acknowledgementTimeout);
            defaults.put(producerPrefix + "configuration.key.serializer", KEY_SERIALIZER);
            defaults.put(producerPrefix + "configuration.value.serializer", VALUE_SERIALIZER);
        });
        long initialBackoff = Binder.get(environment)
                .bind("moduvera.messaging.kafka.consumer-backoff-initial", java.time.Duration.class)
                .orElse(java.time.Duration.ofMillis(100))
                .toMillis();
        long maxBackoff = Binder.get(environment)
                .bind("moduvera.messaging.kafka.consumer-backoff-max", java.time.Duration.class)
                .orElse(java.time.Duration.ofSeconds(1))
                .toMillis();
        consumers.forEach(bindingName -> addConsumerDefaults(
                environment, defaults, bindingName, initialBackoff, maxBackoff));
        environment.getPropertySources().addLast(
                new MapPropertySource("moduveraKafkaOutboxDefaults", defaults));
    }

    private static void addConsumerDefaults(
            ConfigurableEnvironment environment,
            Map<String, Object> defaults,
            String bindingName,
            long initialBackoff,
            long maxBackoff) {
        String bindingPrefix = "spring.cloud.stream.bindings." + bindingName + ".consumer.";
        defaults.put(bindingPrefix + "use-native-decoding", true);
        defaults.put(bindingPrefix + "max-attempts", 1);
        defaults.put(bindingPrefix + "back-off-initial-interval", initialBackoff);
        defaults.put(bindingPrefix + "back-off-max-interval", maxBackoff);
        defaults.put(bindingPrefix + "back-off-multiplier", 2.0d);
        String kafkaPrefix = "spring.cloud.stream.kafka.bindings." + bindingName + ".consumer.";
        defaults.put(kafkaPrefix + "enable-dlq", true);
        defaults.put(
                kafkaPrefix + "dlq-producer-properties.configuration.key.serializer",
                VALUE_SERIALIZER);
        defaults.put(
                kafkaPrefix + "dlq-producer-properties.configuration.value.serializer",
                VALUE_SERIALIZER);
        String destination = environment.getProperty(
                "spring.cloud.stream.bindings." + bindingName + ".destination");
        if (destination != null && !destination.isBlank()) {
            defaults.put(kafkaPrefix + "dlq-name", destination + "-dlq");
        }
    }
}
