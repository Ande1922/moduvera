package io.github.ande1922.moduvera.identifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SnowflakeIdentifierGeneratorTest {

    @Test
    void producesPositiveMonotonicIdentifiersWithinOneMillisecond() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SnowflakeIdentifierGenerator generator =
                new SnowflakeIdentifierGenerator(clock, SnowflakeIdentifierGenerator.DEFAULT_EPOCH, 7, 5);

        long first = generator.nextId();
        long second = generator.nextId();

        assertThat(first).isPositive();
        assertThat(second).isGreaterThan(first);
        assertThat((first >>> 12) & 1023).isEqualTo(7);
    }

    @Test
    void toleratesOnlyTheConfiguredSmallRollback() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00.010Z"));
        SnowflakeIdentifierGenerator generator =
                new SnowflakeIdentifierGenerator(clock, SnowflakeIdentifierGenerator.DEFAULT_EPOCH, 1, 5);
        long first = generator.nextId();

        clock.set(Instant.parse("2026-01-01T00:00:00.007Z"));
        assertThat(generator.nextId()).isGreaterThan(first);

        clock.set(Instant.parse("2025-12-31T23:59:59.900Z"));
        assertThatThrownBy(generator::nextId)
                .isInstanceOf(IdentifierUnavailableException.class)
                .hasMessageContaining("clock moved backwards");
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant newInstant) {
            instant = newInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
