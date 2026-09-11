package io.github.ande1922.moduvera.messaging.kafka;

import static io.github.ande1922.moduvera.messaging.kafka.RelayFixtureSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.outbox.ClaimedOutboxBatch;
import io.github.ande1922.moduvera.message.outbox.ClaimedOutboxMessage;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.message.outbox.OutboxStore;
import io.github.ande1922.moduvera.message.outbox.OutboxWorker;
import io.github.ande1922.moduvera.message.outbox.PublicationLifecycle;
import io.github.ande1922.moduvera.message.outbox.PublicationObserver;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** A real fresh JVM Relay; only the ACK-before-mark checkpoint is a fixture wrapper. */
public final class RelayFixtureChild {
    private RelayFixtureChild() {}

    public static void main(String[] arguments) throws Exception {
        var application = new SpringApplication(Configuration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(properties());
        Path directory = Path.of(System.getenv("RELAY_FIXTURE_DIRECTORY"));
        String mode = System.getenv("RELAY_FIXTURE_MODE");
        try (var context = application.run(); var logs = new Logs(directory.resolve(mode + "-logs.jsonl"))) {
            var properties = context.getBean(ModuveraMessagingKafkaProperties.class);
            var relay = context.getBean(OutboxRelay.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            properties.setRelayEnabled(true);
            relay.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (System.nanoTime() < deadline) {
                String expected = "terminal".equals(mode) ? "TERMINAL" : "PUBLISHED";
                if (expected.equals(jdbc.queryForObject("SELECT status FROM moduvera_message_outbox", String.class))
                        && relay.state() == OutboxRelay.State.WAITING) {
                    write(directory.resolve(mode + "-complete.json"), Map.of("pid", ProcessHandle.current().pid(),
                            "status", expected, "relayState", relay.state().name(), "canonicalCount", logs.lines.stream()
                                    .filter(line -> line.contains("task.execute")).count()));
                    return;
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
            throw new IllegalStateException("child Relay did not reach its expected checkpoint");
        }
    }

    static Map<String, Object> properties() {
        var values = new HashMap<String, Object>();
        values.put("spring.datasource.url", System.getenv("RELAY_FIXTURE_JDBC_URL"));
        values.put("spring.datasource.username", System.getenv("RELAY_FIXTURE_JDBC_USER"));
        values.put("spring.datasource.password", System.getenv("RELAY_FIXTURE_JDBC_PASSWORD"));
        values.put("spring.cloud.stream.kafka.binder.brokers", System.getenv("RELAY_FIXTURE_BROKERS"));
        producerProperties(values);
        return values;
    }

    static void producerProperties(Map<String, Object> values) {
        values.put("spring.cloud.stream.output-bindings", "relay-out-0");
        values.put("spring.cloud.stream.bindings.relay-out-0.destination", TOPIC);
        values.put("spring.cloud.stream.bindings.relay-out-0.producer.use-native-encoding", true);
        String prefix = "spring.cloud.stream.kafka.bindings.relay-out-0.producer.";
        values.put(prefix + "sync", true);
        values.put(prefix + "configuration.key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        values.put(prefix + "configuration.linger.ms", 0);
        values.put(prefix + "configuration.acks", "all");
        for (String timeout : java.util.List.of("delivery.timeout.ms", "request.timeout.ms", "max.block.ms")) {
            values.put(prefix + "configuration." + timeout, 1500);
        }
        values.put("moduvera.messaging.kafka.routes." + TOPIC, "relay-out-0");
        values.put("moduvera.messaging.kafka.relay-business-boundary-destinations[0]", TOPIC);
        values.put("moduvera.messaging.kafka.relay-enabled", false);
        values.put("moduvera.messaging.kafka.relay-batch-size", 1);
        values.put("moduvera.messaging.kafka.claim-lease", "60s");
        values.put("moduvera.messaging.kafka.relay-poll-interval", "1h");
        values.put("moduvera.messaging.kafka.failure-backoff", "0ms");
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Configuration {
        @Bean ObjectMapper fixtureObjectMapper() { return new ObjectMapper(); }

        @Bean OutboxWorker fixtureWorker(OutboxStore store, MessageTransport transport, Clock clock,
                ModuveraMessagingKafkaProperties properties, PublicationObserver observer, PublicationLifecycle lifecycle) {
            OutboxStore selected = "crash".equals(System.getenv("RELAY_FIXTURE_MODE"))
                    ? new CheckpointStore(store, Path.of(System.getenv("RELAY_FIXTURE_DIRECTORY"), "ack-before-mark.json")) : store;
            properties.validateRelayInvariant();
            boolean terminal = "terminal".equals(System.getenv("RELAY_FIXTURE_MODE"));
            MessageTransport selectedTransport = terminal ? message -> { throw new IllegalStateException(PRIVATE_CAUSE); } : transport;
            return new OutboxWorker(selected, selectedTransport, clock, System::nanoTime, properties.getClaimLease(),
                    properties.getLeaseSafetyMargin(), properties.getFailureBackoff(), terminal ? 1 : properties.getRelayMaxAttempts(), observer, lifecycle);
        }

        @Bean OutboxRelay fixtureRelay(OutboxWorker worker, ModuveraMessagingKafkaProperties properties, LocalOutboxWakeSignal signal) {
            return new OutboxRelay(worker, properties, signal);
        }
    }

    private record CheckpointStore(OutboxStore delegate, Path checkpoint) implements OutboxStore {
        @Override public Optional<ClaimedOutboxBatch> claim(int limit, Duration lease) { return delegate.claim(limit, lease); }
        @Override public Optional<ClaimedOutboxMessage> preparePublication(ClaimedOutboxMessage entry, String token) {
            return delegate.preparePublication(entry, token);
        }
        @Override public boolean markPublished(MessageId id, String token, Instant at) {
            assertThat(ExecutionContextHolder.current()).isEmpty();
            try {
                write(checkpoint, Map.of("pid", ProcessHandle.current().pid(), "messageId", id.value(),
                        "checkpoint", "ACK-before-mark", "trace", trace()));
                new CountDownLatch(1).await();
                throw new AssertionError("the crash checkpoint must be terminated by the parent process");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            } catch (Exception failure) {
                throw new IllegalStateException("cannot write child checkpoint", failure);
            }
        }
        @Override public boolean markFailed(MessageId id, String token, Duration delay, String failure) {
            return delegate.markFailed(id, token, delay, failure);
        }
        @Override public boolean markTerminal(MessageId id, String token, Instant at, String failure) {
            return delegate.markTerminal(id, token, at, failure);
        }
    }
}
