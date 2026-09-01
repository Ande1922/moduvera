package io.github.ande1922.moduvera.benchmark.store.candidate;

import io.github.ande1922.moduvera.benchmark.store.shared.Actor;
import io.github.ande1922.moduvera.benchmark.store.shared.ExecutionContext;
import io.github.ande1922.moduvera.benchmark.store.shared.ExecutionContextAccessor;
import io.github.ande1922.moduvera.benchmark.store.shared.Store;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreError;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreId;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreRepository;
import io.github.ande1922.moduvera.benchmark.store.shared.TenantId;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

final class MyBatisStoreRepository implements StoreRepository {
  private final SqlSessionFactory sessions;
  private final ExecutionContextAccessor contexts;
  private final Clock clock;
  private final StoreObjectMapper objects;

  MyBatisStoreRepository(
      SqlSessionFactory sessions,
      ExecutionContextAccessor contexts,
      Clock clock,
      StoreObjectMapper objects) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.contexts = Objects.requireNonNull(contexts, "contexts");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.objects = Objects.requireNonNull(objects, "objects");
  }

  @Override
  public Optional<Store> findById(TenantId tenantId, StoreId storeId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(storeId, "storeId");

    try (SqlSession session = sessions.openSession()) {
      StoreRow row =
          session
              .getMapper(StoreSqlMapper.class)
              .selectById(tenantId.value(), storeId.value());
      return Optional.ofNullable(row).map(objects::toDomain);
    } catch (RuntimeException exception) {
      throw SqlFailureTranslator.translate(exception);
    }
  }

  @Override
  public Store save(Store candidate) {
    Store required = Objects.requireNonNull(candidate, "candidate");
    ExecutionContext context = currentContext();
    if (!context.tenantId().equals(required.tenantId())) {
      throw new StoreError("store.not-found");
    }

    return required.isPersisted()
        ? update(required, context.actor())
        : insert(required, context.actor());
  }

  private Store insert(Store draft, Actor actor) {
    StoreRow row = objects.forInsert(draft, actor, clock.instant());
    try (SqlSession session = sessions.openSession(false)) {
      StoreSqlMapper sql = session.getMapper(StoreSqlMapper.class);
      int affected = sql.insert(row);
      if (affected != 1) {
        throw new StoreError("store.persistence-failure");
      }
      Store stored = requireStored(sql.selectById(row.getTenantId(), row.getStoreId()));
      session.commit();
      return stored;
    } catch (RuntimeException exception) {
      throw SqlFailureTranslator.translate(exception);
    }
  }

  private Store update(Store candidate, Actor actor) {
    StoreRow row = objects.forUpdate(candidate, actor, clock.instant());
    try (SqlSession session = sessions.openSession(false)) {
      StoreSqlMapper sql = session.getMapper(StoreSqlMapper.class);
      int affected = sql.updateWithVersion(row, candidate.version());
      if (affected != 1) {
        throw new StoreError("store.version-conflict");
      }
      Store stored = requireStored(sql.selectById(row.getTenantId(), row.getStoreId()));
      session.commit();
      return stored;
    } catch (RuntimeException exception) {
      throw SqlFailureTranslator.translate(exception);
    }
  }

  private Store requireStored(StoreRow row) {
    if (row == null) {
      throw new StoreError("store.persistence-failure");
    }
    return objects.toDomain(row);
  }

  private ExecutionContext currentContext() {
    return contexts
        .current()
        .orElseThrow(() -> new StoreError("security.context-missing"));
  }
}
