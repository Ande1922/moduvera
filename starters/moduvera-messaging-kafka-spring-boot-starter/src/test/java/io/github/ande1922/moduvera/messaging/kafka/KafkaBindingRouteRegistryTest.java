package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.mock.env.MockEnvironment;

class KafkaBindingRouteRegistryTest {

    @Test
    void rejectsANonCanonicalProducerTopicAtStartup() {
        var properties = properties();
        var bindings = bindings("inventory.reserve.v1", "inventory-results");

        assertThatIllegalStateException()
                .isThrownBy(() -> new KafkaBindingRouteRegistry(
                        properties, bindings, environment("inventory-results-dlq")))
                .withMessageContaining("lowercase, hyphen-separated, version-free")
                .withMessageContaining("inventory.reserve.v1");
    }

    @Test
    void rejectsANonCanonicalConsumerDlqTopicAtStartup() {
        var properties = properties();
        var bindings = bindings("inventory-reserve", "inventory-results");

        assertThatIllegalStateException()
                .isThrownBy(() -> new KafkaBindingRouteRegistry(
                        properties, bindings, environment("inventory.results.dlq")))
                .withMessageContaining("lowercase, hyphen-separated, version-free")
                .withMessageContaining("inventory.results.dlq");
    }

    private static ModuveraMessagingKafkaProperties properties() {
        var properties = new ModuveraMessagingKafkaProperties();
        properties.setRoutes(Map.of("inventory.reserve", "inventory-out-0"));
        properties.setConsumerBindings(List.of("results-in-0"));
        return properties;
    }

    private static BindingServiceProperties bindings(
            String producerTopic, String consumerTopic) {
        var producer = new BindingProperties();
        producer.setDestination(producerTopic);
        var consumer = new BindingProperties();
        consumer.setDestination(consumerTopic);
        consumer.setGroup("order-service");
        var bindings = new BindingServiceProperties();
        bindings.setBindings(Map.of("inventory-out-0", producer, "results-in-0", consumer));
        return bindings;
    }

    private static MockEnvironment environment(String dlqTopic) {
        return new MockEnvironment()
                .withProperty(
                        "spring.cloud.stream.kafka.bindings.inventory-out-0.producer.sync",
                        "true")
                .withProperty(
                        "spring.cloud.stream.kafka.bindings.inventory-out-0.producer.configuration.acks",
                        "all")
                .withProperty(
                        "spring.cloud.stream.kafka.bindings.inventory-out-0.producer.configuration.delivery.timeout.ms",
                        "2000")
                .withProperty(
                        "spring.cloud.stream.kafka.bindings.inventory-out-0.producer.configuration.request.timeout.ms",
                        "2000")
                .withProperty(
                        "spring.cloud.stream.kafka.bindings.inventory-out-0.producer.configuration.max.block.ms",
                        "2000")
                .withProperty(
                        "spring.cloud.stream.kafka.bindings.results-in-0.consumer.dlq-name",
                        dlqTopic);
    }
}
