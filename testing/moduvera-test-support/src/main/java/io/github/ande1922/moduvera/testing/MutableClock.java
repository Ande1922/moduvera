package io.github.ande1922.moduvera.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableClock extends Clock {

    private final AtomicReference<Instant> current;
    private final ZoneId zone;

    public MutableClock(Instant initial, ZoneId zone) {
        this(new AtomicReference<>(Objects.requireNonNull(initial, "initial")), zone);
    }

    private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
        this.current = current;
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public static MutableClock atUtc(Instant initial) {
        return new MutableClock(initial, ZoneOffset.UTC);
    }

    public void set(Instant instant) {
        current.set(Objects.requireNonNull(instant, "instant"));
    }

    public Instant advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("advance duration must not be negative");
        }
        return current.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
        if (zone.equals(requestedZone)) {
            return this;
        }
        return new MutableClock(current, requestedZone);
    }

    @Override
    public Instant instant() {
        return current.get();
    }
}
