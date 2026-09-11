package io.github.ande1922.moduvera.messaging.kafka.autoconfigure;

import io.github.ande1922.moduvera.data.autoconfigure.ModuveraDataMybatisPlusAutoConfiguration;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.message.outbox.OutboxStore;
import io.github.ande1922.moduvera.message.outbox.OutboxWorker;
import io.github.ande1922.moduvera.message.outbox.PublicationObserver;
import io.github.ande1922.moduvera.message.outbox.PublicationLifecycle;
import io.github.ande1922.moduvera.messaging.kafka.RelayPublicationLifecycle;
import io.github.ande1922.moduvera.message.publication.DurablePublication;
import io.github.ande1922.moduvera.message.publication.ImmediatePublication;
import io.github.ande1922.moduvera.messaging.kafka.JdbcDurablePublication;
import io.github.ande1922.moduvera.messaging.kafka.InboundFailureDiagnostics;
import io.github.ande1922.moduvera.messaging.kafka.InboundDeadLetterDiagnostics;
import io.github.ande1922.moduvera.messaging.kafka.InboundRecoveryLogFilter;
import io.github.ande1922.moduvera.messaging.kafka.ImmediateProducerDiagnostics;
import io.github.ande1922.moduvera.messaging.kafka.JdbcInboxRepository;
import io.github.ande1922.moduvera.messaging.kafka.JdbcMessagingDialect;
import io.github.ande1922.moduvera.messaging.kafka.JdbcOutboxStore;
import io.github.ande1922.moduvera.messaging.kafka.KafkaBindingRouteRegistry;
import io.github.ande1922.moduvera.messaging.kafka.KafkaMessageMapper;
import io.github.ande1922.moduvera.messaging.kafka.KafkaImmediatePublication;
import io.github.ande1922.moduvera.messaging.kafka.LocalOutboxWakeSignal;
import io.github.ande1922.moduvera.messaging.kafka.MicrometerPublicationObserver;
import io.github.ande1922.moduvera.messaging.kafka.OutboxMaintenance;
import io.github.ande1922.moduvera.messaging.kafka.OutboxRelay;
import io.github.ande1922.moduvera.messaging.kafka.ModuveraMessagingKafkaProperties;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
import io.github.ande1922.moduvera.messaging.kafka.StreamBridgeMessageTransport;
import java.time.Clock;
import javax.sql.DataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.config.ConsumerEndpointCustomizer;
import org.springframework.integration.kafka.inbound.KafkaMessageDrivenChannelAdapter;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration(after = ModuveraDataMybatisPlusAutoConfiguration.class)
@EnableConfigurationProperties(ModuveraMessagingKafkaProperties.class)
public class ModuveraMessagingKafkaAutoConfiguration {

    @Bean
    static InboundFailureDiagnostics moduveraInboundFailureDiagnostics() {
        return new InboundFailureDiagnostics();
    }

    @Bean
    static ImmediateProducerDiagnostics moduveraImmediateProducerDiagnostics() {
        return new ImmediateProducerDiagnostics();
    }

    @Bean
    InboundDeadLetterDiagnostics moduveraInboundDeadLetterDiagnostics() {
        return new InboundDeadLetterDiagnostics();
    }

    @Bean
    InboundRecoveryLogFilter moduveraInboundRecoveryLogFilter() {
        return new InboundRecoveryLogFilter();
    }

    @Bean
    @ConditionalOnMissingBean(ConsumerEndpointCustomizer.class)
    ConsumerEndpointCustomizer<KafkaMessageDrivenChannelAdapter<?, ?>> moduveraInboundEndpointDiagnostics() {
        return (endpoint, destination, group) -> InboundDeadLetterDiagnostics.configureEndpoint(endpoint);
    }

    @Bean
    @ConditionalOnMissingBean
    Clock moduveraMessagingClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    KafkaMessageMapper moduveraKafkaMessageMapper(ObjectMapper objectMapper) {
        return new KafkaMessageMapper(objectMapper);
    }

    @Bean
    KafkaBindingRouteRegistry moduveraKafkaBindingRoutes(
            ModuveraMessagingKafkaProperties properties,
            BindingServiceProperties bindings,
            Environment environment) {
        return new KafkaBindingRouteRegistry(properties, bindings, environment);
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcMessagingDialect moduveraJdbcMessagingDialect(DataSource dataSource) {
        return dialect(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(OutboxStore.class)
    JdbcOutboxStore moduveraJdbcOutboxStore(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            JdbcMessagingDialect dialect,
            LocalOutboxWakeSignal wakeSignal) {
        requireTransactionManager(transactionManager);
        return new JdbcOutboxStore(
                new NamedParameterJdbcTemplate(dataSource),
                dialect,
                new TransactionTemplate(transactionManager),
                wakeSignal);
    }

    @Bean
    @ConditionalOnMissingBean(DurablePublication.class)
    JdbcDurablePublication moduveraDurablePublication(
            DataSource dataSource, JdbcOutboxStore store) {
        return new JdbcDurablePublication(dataSource, store);
    }

    @Bean
    @ConditionalOnMissingBean
    LocalOutboxWakeSignal moduveraOutboxWakeSignal() {
        return new LocalOutboxWakeSignal();
    }

    @Bean
    @ConditionalOnMissingBean(PublicationObserver.class)
    PublicationObserver moduveraPublicationObserver(ObjectProvider<MeterRegistry> meters) {
        MeterRegistry registry = meters.getIfAvailable();
        return registry == null
                ? PublicationObserver.noop()
                : new MicrometerPublicationObserver(registry);
    }

    @Bean
    @ConditionalOnMissingBean(InboxRepository.class)
    JdbcInboxRepository moduveraJdbcInboxRepository(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            JdbcMessagingDialect dialect) {
        requireTransactionManager(transactionManager);
        return new JdbcInboxRepository(new NamedParameterJdbcTemplate(dataSource), dialect);
    }

    @Bean
    @ConditionalOnMissingBean(MessageTransport.class)
    StreamBridgeMessageTransport moduveraKafkaMessageTransport(
            StreamOperations streamBridge,
            KafkaMessageMapper mapper,
            KafkaBindingRouteRegistry routes) {
        return new StreamBridgeMessageTransport(streamBridge, mapper, routes);
    }

    @Bean
    @ConditionalOnMissingBean(ImmediatePublication.class)
    KafkaImmediatePublication moduveraImmediatePublication(
            MessageTransport transport, ModuveraMessagingKafkaProperties properties) {
        var destinations = java.util.Set.copyOf(properties.getImmediateBusinessBoundaryDestinations());
        if (!properties.getRoutes().keySet().containsAll(destinations)) {
            throw new IllegalStateException("Immediate business boundary destinations require configured logical routes");
        }
        return new KafkaImmediatePublication(transport, destinations);
    }

    @Bean
    @ConditionalOnMissingBean(PublicationLifecycle.class)
    RelayPublicationLifecycle moduveraRelayPublicationLifecycle(ModuveraMessagingKafkaProperties properties) {
        var destinations = java.util.Set.copyOf(properties.getRelayBusinessBoundaryDestinations());
        if (!properties.getRoutes().keySet().containsAll(destinations)) {
            throw new IllegalStateException("Relay business boundary destinations require configured logical routes");
        }
        return new RelayPublicationLifecycle(destinations);
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxWorker moduveraOutboxWorker(
            OutboxStore store,
            MessageTransport transport,
            Clock clock,
            ModuveraMessagingKafkaProperties properties,
            PublicationObserver observer,
            PublicationLifecycle lifecycle) {
        properties.validateRelayInvariant();
        return new OutboxWorker(
                store,
                transport,
                clock,
                System::nanoTime,
                properties.getClaimLease(),
                properties.getLeaseSafetyMargin(),
                properties.getFailureBackoff(),
                properties.getRelayMaxAttempts(),
                observer,
                lifecycle);
    }

    @Bean
    @ConditionalOnMissingBean
    ReliableMessageConsumerFactory moduveraReliableMessageConsumerFactory(
            KafkaMessageMapper mapper,
            ModuveraMessagingKafkaProperties properties) {
        return new ReliableMessageConsumerFactory(
                mapper,
                properties.getConsumerMaxAttempts(),
                properties.getConsumerBackoffInitial(),
                properties.getConsumerBackoffMax());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "moduvera.messaging.kafka",
            name = "relay-enabled",
            havingValue = "true",
            matchIfMissing = true)
    OutboxRelay moduveraOutboxRelay(
            OutboxWorker worker,
            ModuveraMessagingKafkaProperties properties,
            LocalOutboxWakeSignal wakeSignal) {
        return new OutboxRelay(worker, properties, wakeSignal);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcOutboxStore.class)
    OutboxMaintenance moduveraOutboxMaintenance(
            JdbcOutboxStore store,
            PublicationObserver observer,
            ModuveraMessagingKafkaProperties properties,
            Clock clock) {
        return new OutboxMaintenance(store, observer, properties, clock);
    }

    private static void requireTransactionManager(PlatformTransactionManager transactionManager) {
        if (transactionManager == null) {
            throw new IllegalStateException("Kafka messaging requires a PlatformTransactionManager");
        }
    }

    private static JdbcMessagingDialect dialect(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return switch (connection.getMetaData().getDatabaseProductName()) {
                case "PostgreSQL" -> JdbcMessagingDialect.POSTGRESQL;
                case "MySQL" -> JdbcMessagingDialect.MYSQL;
                default -> throw new IllegalStateException("Unsupported messaging database");
            };
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Could not identify messaging database", failure);
        }
    }
}
