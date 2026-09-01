package com.gaopc.benchmark.store.candidate;

import com.gaopc.benchmark.store.shared.ExecutionContext;
import com.gaopc.benchmark.store.shared.ExecutionContextAccessor;
import com.gaopc.benchmark.store.shared.Store;
import com.gaopc.benchmark.store.shared.StoreError;
import com.gaopc.benchmark.store.shared.StoreId;
import com.gaopc.benchmark.store.shared.StoreRepository;
import com.gaopc.benchmark.store.shared.TenantId;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

final class MyBatisStoreRepository implements StoreRepository {
  private static final int MYSQL_DUPLICATE_KEY = 1062;

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
    ExecutionContext context = requireContext();
    if (!context.tenantId().equals(tenantId)) {
      return Optional.empty();
    }

    try (SqlSession session = sessions.openSession()) {
      StoreRow row =
          session
              .getMapper(StoreRowMapper.class)
              .selectByTenantAndId(context.tenantId().value(), storeId.value());
      return Optional.ofNullable(row).map(objects::toDomain);
    } catch (PersistenceException exception) {
      throw translated(exception);
    } catch (RuntimeException exception) {
      throw new StoreError("store.persistence-failure");
    }
  }

  @Override
  public Store save(Store candidate) {
    Objects.requireNonNull(candidate, "candidate");
    ExecutionContext context = requireContext();
    if (!context.tenantId().equals(candidate.tenantId())) {
      throw new StoreError("store.not-found");
    }

    Instant persistedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    try (SqlSession session = sessions.openSession(false)) {
      try {
        StoreRowMapper mapper = session.getMapper(StoreRowMapper.class);
        if (candidate.isPersisted()) {
          update(mapper, candidate, context, persistedAt);
        } else {
          mapper.insert(objects.forInsert(candidate, context.tenantId(), context.actor(), persistedAt));
        }

        StoreRow refreshed =
            mapper.selectByTenantAndId(context.tenantId().value(), candidate.id().value());
        if (refreshed == null) {
          throw new StoreError("store.persistence-failure");
        }
        Store result = objects.toDomain(refreshed);
        session.commit();
        return result;
      } catch (RuntimeException exception) {
        session.rollback();
        throw exception;
      }
    } catch (StoreError error) {
      throw error;
    } catch (PersistenceException exception) {
      throw translated(exception);
    } catch (RuntimeException exception) {
      throw new StoreError("store.persistence-failure");
    }
  }

  private void update(
      StoreRowMapper mapper,
      Store candidate,
      ExecutionContext context,
      Instant persistedAt) {
    StoreRow row =
        objects.forUpdate(candidate, context.tenantId(), context.actor(), persistedAt);
    if (mapper.updateByTenantIdAndVersion(row) != 1) {
      throw new StoreError("store.version-conflict");
    }
  }

  private ExecutionContext requireContext() {
    return contexts.current().orElseThrow(() -> new StoreError("security.context-missing"));
  }

  private static StoreError translated(PersistenceException exception) {
    SQLException sql = findSqlException(exception);
    if (sql != null
        && sql.getErrorCode() == MYSQL_DUPLICATE_KEY
        && containsStoreCodeConstraint(sql.getMessage())) {
      return new StoreError("store.code-conflict");
    }
    return new StoreError("store.persistence-failure");
  }

  private static SQLException findSqlException(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sql) {
        return sql;
      }
      current = current.getCause();
    }
    return null;
  }

  private static boolean containsStoreCodeConstraint(String message) {
    return message != null
        && message.toLowerCase(Locale.ROOT).contains("uk_store_tenant_code");
  }
}
