package io.github.ande1922.moduvera.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class MutableClockTest {

    @Test
    void advancesDeterministicallyAndSharesTimeAcrossZoneViews() {
        var clock = MutableClock.atUtc(Instant.parse("2026-08-30T00:00:00Z"));
        var shanghai = clock.withZone(ZoneId.of("Asia/Shanghai"));

        clock.advance(Duration.ofSeconds(30));

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-08-30T00:00:30Z"));
        assertThat(shanghai.instant()).isEqualTo(clock.instant());
        assertThat(shanghai.getZone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void rejectsNegativeAdvanceButAllowsAnExplicitReset() {
        var clock = MutableClock.atUtc(Instant.parse("2026-08-30T00:00:00Z"));

        assertThatThrownBy(() -> clock.advance(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        clock.set(Instant.parse("2026-08-29T23:59:59Z"));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-08-29T23:59:59Z"));
    }
}
