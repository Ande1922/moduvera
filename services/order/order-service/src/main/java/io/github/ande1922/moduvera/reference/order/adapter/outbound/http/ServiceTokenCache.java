package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import io.github.ande1922.moduvera.context.ActorType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

final class ServiceTokenCache {

    private final Clock clock;
    private final Duration refreshSkew;
    private final int maxEntries;
    private final Object admissionLock = new Object();
    private final ConcurrentMap<Key, Token> cached = new ConcurrentHashMap<>();
    private final ConcurrentMap<Key, CompletableFuture<Token>> refreshes = new ConcurrentHashMap<>();

    ServiceTokenCache(Clock clock, Duration refreshSkew, int maxEntries) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refreshSkew = Objects.requireNonNull(refreshSkew, "refreshSkew");
        if (refreshSkew.isNegative()) {
            throw new IllegalArgumentException("refreshSkew must not be negative");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    String accessToken(Key key, Supplier<Token> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        while (true) {
            Instant now = clock.instant();
            Token existing = cached.get(key);
            if (isSafeToUse(existing, now)) {
                return existing.value();
            }
            if (existing != null) {
                cached.remove(key, existing);
            }

            var refresh = new CompletableFuture<Token>();
            CompletableFuture<Token> inFlight = refreshes.putIfAbsent(key, refresh);
            if (inFlight == null) {
                return refresh(key, loader, refresh);
            }

            Token refreshed = await(inFlight);
            if (isSafeToUse(refreshed, clock.instant())) {
                return refreshed.value();
            }
            cached.remove(key, refreshed);
        }
    }

    private String refresh(Key key, Supplier<Token> loader, CompletableFuture<Token> refresh) {
        try {
            Token loaded = Objects.requireNonNull(loader.get(), "service token loader result");
            if (!isSafeToUse(loaded, clock.instant())) {
                throw new IllegalStateException("service token expires inside the refresh safety window");
            }
            cacheIfCapacityAllows(key, loaded, clock.instant());
            refresh.complete(loaded);
            return loaded.value();
        } catch (RuntimeException | Error failure) {
            cached.remove(key);
            refresh.completeExceptionally(failure);
            throw failure;
        } finally {
            refreshes.remove(key, refresh);
        }
    }

    private boolean isSafeToUse(Token token, Instant now) {
        return token != null && token.expiresAt().isAfter(now.plus(refreshSkew));
    }

    private void cacheIfCapacityAllows(Key key, Token token, Instant now) {
        synchronized (admissionLock) {
            cached.entrySet().removeIf(entry -> !isSafeToUse(entry.getValue(), now));
            if (cached.containsKey(key) || cached.size() < maxEntries) {
                cached.put(key, token);
            }
        }
    }

    private static Token await(CompletableFuture<Token> refresh) {
        try {
            return refresh.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    record Key(
            String serviceId,
            String audience,
            String tenantId,
            ActorType initiatorType,
            String initiatorId) {

        Key {
            Objects.requireNonNull(serviceId, "serviceId");
            Objects.requireNonNull(audience, "audience");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(initiatorType, "initiatorType");
            Objects.requireNonNull(initiatorId, "initiatorId");
        }
    }

    record Token(String value, Instant expiresAt) {

        Token {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (value.isBlank()) {
                throw new IllegalArgumentException("service token must not be blank");
            }
        }
    }
}
