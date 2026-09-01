package io.github.ande1922.moduvera.identifier;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class SnowflakeIdentifierGenerator implements IdentifierGenerator {

    public static final Instant DEFAULT_EPOCH = Instant.parse("2024-01-01T00:00:00Z");

    private static final int SEQUENCE_BITS = 12;
    private static final int WORKER_BITS = 10;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final long MAX_WORKER_ID = (1L << WORKER_BITS) - 1;
    private static final long MAX_TIMESTAMP_DELTA = (1L << 41) - 1;

    private final Clock clock;
    private final long epochMillis;
    private final long workerId;
    private final long toleratedRollbackMillis;

    private long lastTimestamp = -1;
    private long sequence;

    public SnowflakeIdentifierGenerator(long workerId) {
        this(Clock.systemUTC(), DEFAULT_EPOCH, workerId, 5);
    }

    public SnowflakeIdentifierGenerator(
            Clock clock, Instant epoch, long workerId, long toleratedRollbackMillis) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.epochMillis = Objects.requireNonNull(epoch, "epoch").toEpochMilli();
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
        }
        if (toleratedRollbackMillis < 0) {
            throw new IllegalArgumentException("toleratedRollbackMillis must not be negative");
        }
        this.workerId = workerId;
        this.toleratedRollbackMillis = toleratedRollbackMillis;
    }

    @Override
    public synchronized long nextId() {
        long observedTimestamp = clock.millis();
        long timestamp = normalizeTimestamp(observedTimestamp);

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                throw new IdentifierUnavailableException("identifier sequence exhausted for the current millisecond");
            }
        } else {
            sequence = 0;
        }

        long delta = timestamp - epochMillis;
        if (delta < 0 || delta > MAX_TIMESTAMP_DELTA) {
            throw new IdentifierUnavailableException("clock is outside the configured identifier epoch range");
        }

        lastTimestamp = timestamp;
        return (delta << (WORKER_BITS + SEQUENCE_BITS)) | (workerId << SEQUENCE_BITS) | sequence;
    }

    private long normalizeTimestamp(long observedTimestamp) {
        if (lastTimestamp < 0 || observedTimestamp >= lastTimestamp) {
            return observedTimestamp;
        }
        long rollback = lastTimestamp - observedTimestamp;
        if (rollback > toleratedRollbackMillis) {
            throw new IdentifierUnavailableException(
                    "clock moved backwards by " + rollback + "ms; refusing to risk duplicate identifiers");
        }
        return lastTimestamp;
    }
}
