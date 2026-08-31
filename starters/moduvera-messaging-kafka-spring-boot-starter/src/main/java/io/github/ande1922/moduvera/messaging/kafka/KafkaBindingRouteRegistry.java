package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import java.util.Map;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.core.env.Environment;

public final class KafkaBindingRouteRegistry {

    private final Map<String, String> routes;

    public KafkaBindingRouteRegistry(
            ModuveraMessagingKafkaProperties properties,
            BindingServiceProperties bindings,
            Environment environment) {
        routes = Map.copyOf(properties.getRoutes());
        if (routes.isEmpty()) {
            throw new IllegalStateException("moduvera.messaging.kafka.routes must not be empty");
        }
        routes.forEach((logicalDestination, bindingName) -> validate(
                logicalDestination,
                bindingName,
                bindings,
                environment,
                properties.getBrokerAckTimeout().toMillis()));
        properties.getConsumerBindings()
                .forEach(bindingName -> validateConsumer(bindingName, bindings, environment));
    }

    private static void validateConsumer(
            String bindingName,
            BindingServiceProperties bindings,
            Environment environment) {
        BindingProperties binding = bindings.getBindings().get(bindingName);
        if (bindingName == null
                || bindingName.isBlank()
                || binding == null
                || binding.getDestination() == null
                || binding.getDestination().isBlank()
                || binding.getGroup() == null
                || binding.getGroup().isBlank()) {
            throw new IllegalStateException(
                    "Kafka consumer binding requires an explicit destination and group: "
                            + bindingName);
        }
        KafkaTopicName.requireValid(
                binding.getDestination(), "Kafka consumer binding " + bindingName);
        String dlqName = environment.getProperty(
                "spring.cloud.stream.kafka.bindings." + bindingName + ".consumer.dlq-name");
        if (dlqName != null) {
            KafkaTopicName.requireValid(dlqName, "Kafka consumer DLQ binding " + bindingName);
        }
    }

    public String bindingFor(Destination destination) {
        String bindingName = routes.get(destination.value());
        if (bindingName == null) {
            throw new NonRetryableMessageException(
                    "no Kafka binding is configured for logical destination " + destination.value());
        }
        return bindingName;
    }

    private static void validate(
            String logicalDestination,
            String bindingName,
            BindingServiceProperties bindings,
            Environment environment,
            long acknowledgementTimeoutMillis) {
        if (logicalDestination == null
                || logicalDestination.isBlank()
                || bindingName == null
                || bindingName.isBlank()) {
            throw new IllegalStateException("Kafka route names must not be blank");
        }
        BindingProperties binding = bindings.getBindings().get(bindingName);
        if (binding == null || binding.getDestination() == null || binding.getDestination().isBlank()) {
            throw new IllegalStateException(
                    "Kafka route " + logicalDestination + " requires a configured binding destination");
        }
        KafkaTopicName.requireValid(
                binding.getDestination(), "Kafka producer binding " + bindingName);
        String producerPrefix = "spring.cloud.stream.kafka.bindings." + bindingName + ".producer.";
        if (!environment.getProperty(producerPrefix + "sync", Boolean.class, false)
                || !"all".equals(environment.getProperty(producerPrefix + "configuration.acks"))
                || !isBoundedTimeout(
                        environment,
                        producerPrefix + "configuration.delivery.timeout.ms",
                        acknowledgementTimeoutMillis)
                || !isBoundedTimeout(
                        environment,
                        producerPrefix + "configuration.request.timeout.ms",
                        acknowledgementTimeoutMillis)
                || !isBoundedTimeout(
                        environment,
                        producerPrefix + "configuration.max.block.ms",
                        acknowledgementTimeoutMillis)) {
            throw new IllegalStateException(
                    "Kafka outbox binding " + bindingName
                            + " must use sync broker acknowledgements with bounded timeouts");
        }
    }

    private static boolean isBoundedTimeout(
            Environment environment, String property, long maximumMillis) {
        long value = environment.getProperty(property, Long.class, -1L);
        return value > 0L && value <= maximumMillis;
    }
}
