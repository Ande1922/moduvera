package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class KafkaTopicNameTest {

    @Test
    void acceptsOnlyLowercaseHyphenSeparatedVersionFreeTopics() {
        assertThatCode(() -> KafkaTopicName.requireValid(
                        "inventory-reservation-results", "test binding"))
                .doesNotThrowAnyException();
        assertThatCode(() -> KafkaTopicName.requireValid("orders", "test binding"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDotsUnderscoresUppercaseMalformedSeparatorsAndVersions() {
        assertInvalid("inventory.reserve");
        assertInvalid("inventory_reserve");
        assertInvalid("Inventory-reserve");
        assertInvalid("inventory--reserve");
        assertInvalid("inventory-reserve-v1");
        assertInvalid("inventory-v2-reserve");
    }

    private static void assertInvalid(String topic) {
        assertThatIllegalStateException()
                .isThrownBy(() -> KafkaTopicName.requireValid(topic, "test binding"))
                .withMessageContaining("lowercase, hyphen-separated, version-free")
                .withMessageContaining(topic);
    }
}
