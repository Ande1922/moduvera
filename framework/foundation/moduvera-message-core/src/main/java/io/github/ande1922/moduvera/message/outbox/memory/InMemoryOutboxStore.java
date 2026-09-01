package io.github.ande1922.moduvera.message.outbox.memory;

import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.outbox.ClaimedOutboxBatch;
import io.github.ande1922.moduvera.message.outbox.ClaimedOutboxMessage;
import io.github.ande1922.moduvera.message.outbox.OutboxAdministration;
import io.github.ande1922.moduvera.message.outbox.OutboxBacklog;
import io.github.ande1922.moduvera.message.outbox.OutboxStore;
import io.github.ande1922.moduvera.message.outbox.TerminalOutboxMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryOutboxStore implements OutboxStore, OutboxAdministration {

    private final Map<MessageId, Entry> entries = new LinkedHashMap<>();
    private final Clock clock;

    public InMemoryOutboxStore() {
        this(Clock.systemUTC());
    }

    public InMemoryOutboxStore(Clock clock) {
        this.clock = clock;
    }

    synchronized void appendIntent(SerializedMessage message) {
        MessageId id = message.descriptor().id();
        if (entries.putIfAbsent(id, new Entry(message)) != null) {
            throw new IllegalStateException("outbox message already exists: " + id.value());
        }
    }

    @Override
    public synchronized Optional<ClaimedOutboxBatch> claim(int limit, Duration lease) {
        if (limit < 1 || lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("positive claim limit and lease are required");
        }
        Instant now = clock.instant();
        String batchToken = UUID.randomUUID().toString();
        List<Entry> claimed = entries.values().stream()
                .filter(entry -> entry.status == Status.PENDING)
                .filter(entry -> !entry.nextAttemptAt.isAfter(now))
                .filter(entry -> entry.claimExpiresAt == null || !entry.claimExpiresAt.isAfter(now))
                .filter(entry -> !hasEarlierPending(entry))
                .sorted(Comparator.comparing((Entry entry) -> entry.nextAttemptAt)
                        .thenComparing(entry -> entry.message.descriptor().time())
                        .thenComparing(entry -> entry.message.descriptor().id().value()))
                .limit(limit)
                .toList();
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        claimed.forEach(entry -> entry.claim(batchToken, now.plus(lease)));
        return Optional.of(new ClaimedOutboxBatch(
                batchToken,
                claimed.stream()
                        .map(entry -> new ClaimedOutboxMessage(entry.message, entry.failedAttempts))
                        .toList()));
    }

    @Override
    public synchronized boolean markPublished(MessageId id, String claimToken, Instant publishedAt) {
        Entry entry = currentClaim(id, claimToken);
        if (entry == null) {
            return false;
        }
        entry.status = Status.PUBLISHED;
        entry.publishedAt = publishedAt;
        entry.claimToken = null;
        entry.claimExpiresAt = null;
        entry.safeFailure = null;
        return true;
    }

    @Override
    public synchronized boolean markFailed(
            MessageId id, String claimToken, Duration retryDelay, String safeFailure) {
        if (retryDelay == null || retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must not be negative");
        }
        Entry entry = currentClaim(id, claimToken);
        if (entry == null) {
            return false;
        }
        entry.failedAttempts++;
        entry.nextAttemptAt = clock.instant().plus(retryDelay);
        entry.safeFailure = boundedFailure(safeFailure);
        entry.claimToken = null;
        entry.claimExpiresAt = null;
        return true;
    }

    @Override
    public synchronized boolean markTerminal(
            MessageId id, String claimToken, Instant terminalAt, String safeFailure) {
        Entry entry = currentClaim(id, claimToken);
        if (entry == null) {
            return false;
        }
        entry.failedAttempts++;
        entry.status = Status.TERMINAL;
        entry.terminalAt = terminalAt;
        entry.safeFailure = boundedFailure(safeFailure);
        entry.claimExpiresAt = null;
        return true;
    }

    @Override
    public synchronized List<TerminalOutboxMessage> findTerminal(int limit) {
        requireLimit(limit);
        return entries.values().stream()
                .filter(entry -> entry.status == Status.TERMINAL)
                .sorted(Comparator.comparing((Entry entry) -> entry.terminalAt)
                        .thenComparing(entry -> entry.message.descriptor().id().value()))
                .limit(limit)
                .map(entry -> new TerminalOutboxMessage(
                        entry.message.descriptor().id(),
                        entry.message.descriptor().type(),
                        entry.message.descriptor().destination(),
                        entry.terminalAt,
                        entry.safeFailure,
                        entry.claimToken))
                .toList();
    }

    @Override
    public synchronized boolean redrive(MessageId id, String redriveToken) {
        Entry entry = entries.get(id);
        if (entry == null
                || entry.status != Status.TERMINAL
                || redriveToken == null
                || !redriveToken.equals(entry.claimToken)) {
            return false;
        }
        entry.status = Status.PENDING;
        entry.nextAttemptAt = clock.instant();
        entry.terminalAt = null;
        entry.safeFailure = null;
        entry.claimToken = null;
        entry.claimExpiresAt = null;
        return true;
    }

    @Override
    public synchronized int deletePublishedBefore(Instant retentionCutoff, int limit) {
        requireLimit(limit);
        List<MessageId> doomed = entries.entrySet().stream()
                .filter(entry -> entry.getValue().status == Status.PUBLISHED)
                .filter(entry -> entry.getValue().publishedAt.isBefore(retentionCutoff))
                .sorted(Map.Entry.comparingByValue(Comparator.comparing(entry -> entry.publishedAt)))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
        doomed.forEach(entries::remove);
        return doomed.size();
    }

    @Override
    public synchronized OutboxBacklog backlog() {
        Instant now = clock.instant();
        List<Entry> pending = entries.values().stream()
                .filter(entry -> entry.status == Status.PENDING)
                .toList();
        Instant oldest = pending.stream()
                .map(entry -> entry.message.descriptor().time())
                .min(Instant::compareTo)
                .orElse(now);
        long terminal = entries.values().stream()
                .filter(entry -> entry.status == Status.TERMINAL)
                .count();
        Duration oldestAge = oldest.isAfter(now) ? Duration.ZERO : Duration.between(oldest, now);
        return new OutboxBacklog(pending.size(), oldestAge, terminal);
    }

    public synchronized boolean isPublished(MessageId id) {
        Entry entry = entries.get(id);
        return entry != null && entry.status == Status.PUBLISHED;
    }

    public synchronized boolean isTerminal(MessageId id) {
        Entry entry = entries.get(id);
        return entry != null && entry.status == Status.TERMINAL;
    }

    private boolean hasEarlierPending(Entry candidate) {
        var descriptor = candidate.message.descriptor();
        return entries.values().stream()
                .filter(entry -> entry != candidate && entry.status == Status.PENDING)
                .filter(entry -> entry.message.descriptor().destination().equals(descriptor.destination()))
                .filter(entry -> entry.message.descriptor().partitionKey().equals(descriptor.partitionKey()))
                .anyMatch(entry -> isEarlier(entry.message, candidate.message));
    }

    private static boolean isEarlier(SerializedMessage left, SerializedMessage right) {
        int time = left.descriptor().time().compareTo(right.descriptor().time());
        return time < 0
                || (time == 0
                        && left.descriptor()
                                        .id()
                                        .value()
                                        .compareTo(right.descriptor().id().value())
                                < 0);
    }

    private Entry currentClaim(MessageId id, String claimToken) {
        Entry entry = entries.get(id);
        return entry != null
                        && entry.status == Status.PENDING
                        && claimToken != null
                        && claimToken.equals(entry.claimToken)
                ? entry
                : null;
    }

    private static String boundedFailure(String failure) {
        if (failure == null || failure.isBlank()) {
            return "UnknownFailure";
        }
        return failure.length() <= 256 ? failure : failure.substring(0, 256);
    }

    private static void requireLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private enum Status {
        PENDING,
        PUBLISHED,
        TERMINAL
    }

    private static final class Entry {

        private final SerializedMessage message;
        private Instant nextAttemptAt = Instant.EPOCH;
        private Instant claimExpiresAt;
        private Instant publishedAt;
        private Instant terminalAt;
        private String claimToken;
        private String safeFailure;
        private int failedAttempts;
        private Status status = Status.PENDING;

        private Entry(SerializedMessage message) {
            this.message = message;
        }

        private void claim(String token, Instant expiresAt) {
            claimToken = token;
            claimExpiresAt = expiresAt;
        }
    }
}
