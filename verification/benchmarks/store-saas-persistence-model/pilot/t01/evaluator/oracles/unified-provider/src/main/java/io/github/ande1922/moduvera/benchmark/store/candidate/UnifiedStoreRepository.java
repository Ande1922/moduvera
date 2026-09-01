package io.github.ande1922.moduvera.benchmark.store.candidate;

import io.github.ande1922.moduvera.benchmark.store.shared.Actor;
import io.github.ande1922.moduvera.benchmark.store.shared.ExecutionContext;
import io.github.ande1922.moduvera.benchmark.store.shared.ExecutionContextAccessor;
import io.github.ande1922.moduvera.benchmark.store.shared.Store;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreError;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreId;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreRepository;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreStatus;
import io.github.ande1922.moduvera.benchmark.store.shared.TenantId;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

final class UnifiedStoreRepository implements StoreRepository {
  private static final String PERSISTENCE_FAILURE = "store.persistence-failure";

  private final SqlSessionFactory sessions;
  private final ExecutionContextAccessor contexts;
  private final Clock clock;

  UnifiedStoreRepository(
      SqlSessionFactory sessions, ExecutionContextAccessor contexts, Clock clock) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.contexts = Objects.requireNonNull(contexts, "contexts");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Optional<Store> findById(TenantId tenantId, StoreId storeId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(storeId, "storeId");
    try (SqlSession session = sessions.openSession(true)) {
      return Optional.ofNullable(session.getMapper(StoreSqlMapper.class).selectById(tenantId, storeId));
    } catch (RuntimeException exception) {
      throw translate(exception);
    }
  }

  @Override
  public Store save(Store candidate) {
    Objects.requireNonNull(candidate, "candidate");
    ExecutionContext context =
        contexts.current().orElseThrow(() -> new StoreError("security.context-missing"));
    if (!candidate.tenantId().equals(context.tenantId())) {
      throw new StoreError("store.not-found");
    }

    Instant auditAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Actor actor = context.actor();
    try (SqlSession session = sessions.openSession(false)) {
      StoreSqlMapper mapper = session.getMapper(StoreSqlMapper.class);
      if (candidate.isPersisted()) {
        compareAndSet(mapper, candidate, auditAt, actor);
      } else if (mapper.insert(candidate, auditAt, actor) != 1) {
        throw new StoreError(PERSISTENCE_FAILURE);
      }

      Store refreshed = mapper.selectById(candidate.tenantId(), candidate.id());
      if (refreshed == null) {
        throw new StoreError(PERSISTENCE_FAILURE);
      }
      session.commit();
      return refreshed;
    } catch (StoreError exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw translate(exception);
    }
  }

  private static void compareAndSet(
      StoreSqlMapper mapper, Store candidate, Instant auditAt, Actor actor) {
    if (mapper.compareAndSet(candidate, auditAt, actor) == 1) {
      return;
    }
    Store winner = mapper.selectById(candidate.tenantId(), candidate.id());
    if (winner == null) {
      throw new StoreError("store.not-found");
    }
    if (winner.status() != StoreStatus.ACTIVE) {
      throw new StoreError("store.state-conflict");
    }
    throw new StoreError("store.version-conflict");
  }

  private static StoreError translate(RuntimeException exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException
          && sqlException.getErrorCode() == 1062
          && containsCodeKey(sqlException.getMessage())) {
        return new StoreError("store.code-conflict", exception);
      }
    }
    return new StoreError(PERSISTENCE_FAILURE, exception);
  }

  private static boolean containsCodeKey(String message) {
    return message != null
        && message.toLowerCase(Locale.ROOT).contains("uk_store_tenant_code");
  }
}
