package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.publication.DurablePublication;
import io.github.ande1922.moduvera.message.publication.ImmediatePublication;
import io.github.ande1922.moduvera.messaging.kafka.autoconfigure.ModuveraMessagingKafkaAutoConfiguration;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.MimeType;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import tools.jackson.databind.ObjectMapper;

class ModuveraMessagingKafkaAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModuveraMessagingKafkaAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(DataSource.class, DriverManagerDataSource::new)
            .withBean(JdbcMessagingDialect.class, () -> JdbcMessagingDialect.POSTGRESQL)
            .withBean(
                    PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(new DriverManagerDataSource()))
            .withBean(TransactionBoundary.class, NoOpTransactionBoundary::new)
            .withBean(StreamOperations.class, AcceptingStreamOperations::new)
            .withBean(
                    "integrationConversionService",
                    ConversionService.class,
                    DefaultConversionService::new)
            .withBean(BindingServiceProperties.class, ModuveraMessagingKafkaAutoConfigurationTest::bindings);

    @Test
    void providesBothExplicitPublicationGuaranteesForAnExplicitLogicalRoute() {
        runner.withPropertyValues(
                        "moduvera.messaging.kafka.routes[inventory.commands]=inventoryCommands-out-0",
                        "spring.cloud.stream.kafka.bindings.inventoryCommands-out-0.producer.sync=true",
                        "spring.cloud.stream.kafka.bindings.inventoryCommands-out-0.producer.configuration.acks=all",
                        "spring.cloud.stream.kafka.bindings.inventoryCommands-out-0.producer.configuration.delivery.timeout.ms=2000",
                        "spring.cloud.stream.kafka.bindings.inventoryCommands-out-0.producer.configuration.request.timeout.ms=2000",
                        "spring.cloud.stream.kafka.bindings.inventoryCommands-out-0.producer.configuration.max.block.ms=2000",
                        "moduvera.messaging.kafka.relay-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JdbcOutboxStore.class);
                    assertThat(context).hasSingleBean(DurablePublication.class);
                    assertThat(context).hasSingleBean(JdbcDurablePublication.class);
                    assertThat(context).hasSingleBean(ImmediatePublication.class);
                    assertThat(context).hasSingleBean(KafkaImmediatePublication.class);
                    assertThat(context).hasSingleBean(JdbcInboxRepository.class);
                    assertThat(context).hasSingleBean(ReliableMessageConsumerFactory.class);
                    assertThat(context).hasSingleBean(StreamBridgeMessageTransport.class);
                    assertThat(context).hasSingleBean(LocalOutboxWakeSignal.class);
                    assertThat(context).hasSingleBean(io.github.ande1922.moduvera.message.outbox.PublicationObserver.class);
                    assertThat(context).doesNotHaveBean(OutboxRelay.class);
                });
    }

    @Test
    void failsFastInsteadOfCreatingAnUnroutedTransport() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    private static BindingServiceProperties bindings() {
        var properties = new BindingServiceProperties();
        var binding = new BindingProperties();
        binding.setDestination("inventory-commands");
        properties.setBindings(Map.of("inventoryCommands-out-0", binding));
        return properties;
    }

    private static final class NoOpTransactionBoundary implements TransactionBoundary {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }

    private static final class AcceptingStreamOperations implements StreamOperations {
        @Override
        public boolean send(String bindingName, Object data) {
            return true;
        }

        @Override
        public boolean send(String bindingName, Object data, MimeType outputContentType) {
            return true;
        }

        @Override
        public boolean send(String bindingName, String binderName, Object data) {
            return true;
        }

        @Override
        public boolean send(
                String bindingName, String binderName, Object data, MimeType outputContentType) {
            return true;
        }
    }
}
