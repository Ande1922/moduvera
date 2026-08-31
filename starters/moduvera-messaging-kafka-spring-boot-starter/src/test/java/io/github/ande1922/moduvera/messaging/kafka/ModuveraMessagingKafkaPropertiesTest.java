package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ModuveraMessagingKafkaPropertiesTest {

    @Test
    void acceptsAClaimLeaseAndShutdownGraceThatCoverTheWorstCaseBatch() {
        var properties = new ModuveraMessagingKafkaProperties();
        properties.setRelayBatchSize(4);
        properties.setBrokerAckTimeout(Duration.ofSeconds(2));
        properties.setDatabaseStateUpdateBudget(Duration.ofSeconds(1));
        properties.setLeaseSafetyMargin(Duration.ofSeconds(1));
        properties.setClaimLease(Duration.ofSeconds(10));
        properties.setShutdownGrace(Duration.ofSeconds(10));

        assertThatCode(properties::validateRelayInvariant).doesNotThrowAnyException();
    }

    @Test
    void rejectsAClaimLeaseOrShutdownGraceShorterThanTheWorstCaseBatch() {
        var properties = new ModuveraMessagingKafkaProperties();
        properties.setRelayBatchSize(4);
        properties.setBrokerAckTimeout(Duration.ofSeconds(2));
        properties.setDatabaseStateUpdateBudget(Duration.ofSeconds(1));
        properties.setLeaseSafetyMargin(Duration.ofSeconds(1));
        properties.setClaimLease(Duration.ofSeconds(9));
        properties.setShutdownGrace(Duration.ofSeconds(8));

        assertThatIllegalStateException()
                .isThrownBy(properties::validateRelayInvariant)
                .withMessageContaining("ACK budget");
    }
}
