package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ServiceTokenCacheTest {

    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(30);

    private final MutableClock clock = MutableClock.atUtc(NOW);
    private final ServiceTokenCache cache = new ServiceTokenCache(clock, REFRESH_SKEW, 32);

    @Test
    void reusesOneTokenOnlyForTheCompleteSecurityScope() {
        AtomicInteger loads = new AtomicInteger();
        ServiceTokenCache.Key first = key("order-service", "catalog-service", "tenant-a", ActorType.USER, "user-1");

        assertThat(cache.accessToken(first, () -> token("token-1", loads))).isEqualTo("token-1");
        assertThat(cache.accessToken(first, () -> token("unused", loads))).isEqualTo("token-1");
        assertThat(cache.accessToken(
                        key("other-service", "catalog-service", "tenant-a", ActorType.USER, "user-1"),
                        () -> token("token-2", loads)))
                .isEqualTo("token-2");
        assertThat(cache.accessToken(
                        key("order-service", "other-audience", "tenant-a", ActorType.USER, "user-1"),
                        () -> token("token-3", loads)))
                .isEqualTo("token-3");
        assertThat(cache.accessToken(
                        key("order-service", "catalog-service", "tenant-b", ActorType.USER, "user-1"),
                        () -> token("token-4", loads)))
                .isEqualTo("token-4");
        assertThat(cache.accessToken(
                        key("order-service", "catalog-service", "tenant-a", ActorType.SERVICE, "user-1"),
                        () -> token("token-5", loads)))
                .isEqualTo("token-5");
        assertThat(cache.accessToken(
                        key("order-service", "catalog-service", "tenant-a", ActorType.USER, "user-2"),
                        () -> token("token-6", loads)))
                .isEqualTo("token-6");

        assertThat(loads).hasValue(6);
    }

    @Test
    void refreshesBeforeExpiryAndNeverFallsBackToTheOldToken() {
        ServiceTokenCache.Key key = key("order-service", "catalog-service", "tenant-a", ActorType.USER, "user-1");
        assertThat(cache.accessToken(key, () -> new ServiceTokenCache.Token("old", NOW.plusSeconds(60))))
                .isEqualTo("old");

        clock.advance(Duration.ofSeconds(31));

        assertThatThrownBy(() -> cache.accessToken(key, () -> {
                    throw new IllegalStateException("identity unavailable");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("identity unavailable");
        assertThat(cache.accessToken(
                        key, () -> new ServiceTokenCache.Token("new", clock.instant().plusSeconds(60))))
                .isEqualTo("new");
    }

    @Test
    void rejectsARefreshedTokenInsideTheSafetyWindow() {
        ServiceTokenCache.Key key = key("order-service", "catalog-service", "tenant-a", ActorType.USER, "user-1");

        assertThatThrownBy(() -> cache.accessToken(
                        key,
                        () -> new ServiceTokenCache.Token(
                                "too-short", clock.instant().plus(REFRESH_SKEW))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("service token expires inside the refresh safety window");
    }

    @Test
    void coalescesConcurrentRefreshesForTheSameScope() throws Exception {
        ServiceTokenCache.Key key = key("order-service", "catalog-service", "tenant-a", ActorType.USER, "user-1");
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<String>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                results.add(executor.submit(() -> cache.accessToken(key, () -> {
                    loads.incrementAndGet();
                    loaderStarted.countDown();
                    await(releaseLoader);
                    return new ServiceTokenCache.Token("shared", NOW.plusSeconds(60));
                })));
            }

            assertThat(loaderStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseLoader.countDown();
            for (var result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("shared");
            }
            assertThat(loads).hasValue(1);
        } finally {
            releaseLoader.countDown();
        }
    }

    @Test
    void doesNotGrowPastTheConfiguredSecurityScopeCapacity() {
        var bounded = new ServiceTokenCache(clock, REFRESH_SKEW, 1);
        AtomicInteger loads = new AtomicInteger();
        ServiceTokenCache.Key first = key("order-service", "catalog-service", "tenant-a", ActorType.USER, "user-1");
        ServiceTokenCache.Key second = key("order-service", "catalog-service", "tenant-a", ActorType.USER, "user-2");

        assertThat(bounded.accessToken(first, () -> token("first", loads))).isEqualTo("first");
        assertThat(bounded.accessToken(second, () -> token("second-1", loads))).isEqualTo("second-1");
        assertThat(bounded.accessToken(second, () -> token("second-2", loads))).isEqualTo("second-2");

        assertThat(loads).hasValue(3);
    }

    private ServiceTokenCache.Token token(String value, AtomicInteger loads) {
        loads.incrementAndGet();
        return new ServiceTokenCache.Token(value, clock.instant().plusSeconds(60));
    }

    private static ServiceTokenCache.Key key(
            String serviceId,
            String audience,
            String tenantId,
            ActorType initiatorType,
            String initiatorId) {
        return new ServiceTokenCache.Key(serviceId, audience, tenantId, initiatorType, initiatorId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", interrupted);
        }
    }
}
