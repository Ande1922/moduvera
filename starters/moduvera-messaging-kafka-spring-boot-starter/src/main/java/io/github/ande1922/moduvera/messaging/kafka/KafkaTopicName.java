package io.github.ande1922.moduvera.messaging.kafka;

import java.util.Objects;
import java.util.regex.Pattern;

public final class KafkaTopicName {

    private static final int KAFKA_MAX_LENGTH = 249;
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
    private static final Pattern VERSION_SEGMENT = Pattern.compile("(?:^|-)v[0-9]+(?:-|$)");

    private KafkaTopicName() {}

    public static void requireValid(String topic, String owner) {
        Objects.requireNonNull(owner, "owner");
        if (topic == null
                || topic.length() > KAFKA_MAX_LENGTH
                || !FORMAT.matcher(topic).matches()
                || VERSION_SEGMENT.matcher(topic).find()) {
            throw new IllegalStateException(owner
                    + " must use a lowercase, hyphen-separated, version-free Kafka topic name: "
                    + topic);
        }
    }
}
