package com.gaopc.benchmark.store.candidate;

import com.gaopc.benchmark.store.shared.ExecutionContext;
import com.gaopc.benchmark.store.shared.Store;
import com.gaopc.benchmark.store.shared.StoreError;
import com.gaopc.benchmark.store.shared.StoreId;
import com.gaopc.benchmark.store.shared.StorePersistenceDependencies;
import com.gaopc.benchmark.store.shared.StoreRepository;
import com.gaopc.benchmark.store.shared.TenantId;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

final class CandidateStoreRepository implements StoreRepository {
  private static final String PERSISTENCE_FAILURE = "store.persistence-failure";
  private final SqlSessionFactory sessions;
  private final StorePersistenceDependencies dependencies;

  CandidateStoreRepository(
      SqlSessionFactory sessions, StorePersistenceDependencies dependencies) {
    this.sessions = sessions;
    this.dependencies = dependencies;
  }

  @Override
  public Optional<Store> findById(TenantId tenantId, StoreId storeId) {
    if (tenantId == null || storeId == null) {
      throw new StoreError("request.validation-failed");
    }
    ExecutionContext context = requireContext();
    if (!context.tenantId().equals(tenantId)) {
      return Optional.empty();
    }

    try (SqlSession session = sessions.openSession()) {
      CandidateStoreMapper mapper = session.getMapper(CandidateStoreMapper.class);
      return Optional.ofNullable(
          mapper.findById(context.tenantId().value(), storeId.value()));
    } catch (RuntimeException failure) {
      throw translate(failure);
    }
  }

  @Override
  public Store save(Store candidate) {
    if (candidate == null) {
      throw new StoreError("request.validation-failed");
    }
    ExecutionContext context = requireContext();
    if (!context.tenantId().equals(candidate.tenantId())) {
      throw new StoreError("store.not-found");
    }

    Instant auditTime = normalizedNow(dependencies.clock());
    try (SqlSession session = sessions.openSession(false)) {
      CandidateStoreMapper mapper = session.getMapper(CandidateStoreMapper.class);
      String tenantId = context.tenantId().value();
      int affected;
      if (candidate.isPersisted()) {
        affected =
            mapper.updateByVersion(
                tenantId,
                candidate,
                context.actor().type(),
                context.actor().subjectId(),
                auditTime);
        if (affected == 0) {
          throw new StoreError("store.version-conflict");
        }
      } else {
        affected =
            mapper.insert(
                tenantId,
                candidate,
                context.actor().type(),
                context.actor().subjectId(),
                auditTime);
        if (affected != 1) {
          throw new StoreError(PERSISTENCE_FAILURE);
        }
      }

      Store refreshed = mapper.findById(tenantId, candidate.id().value());
      if (refreshed == null) {
        throw new StoreError(PERSISTENCE_FAILURE);
      }
      session.commit();
      return refreshed;
    } catch (RuntimeException failure) {
      throw translate(failure);
    }
  }

  private ExecutionContext requireContext() {
    return dependencies
        .contextAccessor()
        .current()
        .orElseThrow(() -> new StoreError("security.context-missing"));
  }

  private static Instant normalizedNow(Clock clock) {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static StoreError translate(RuntimeException failure) {
    if (failure instanceof StoreError storeError) {
      return storeError;
    }
    if (isStoreCodeConflict(failure)) {
      return new StoreError("store.code-conflict");
    }
    return new StoreError(PERSISTENCE_FAILURE);
  }

  private static boolean isStoreCodeConflict(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sqlException
          && sqlException.getErrorCode() == 1062
          && mentionsStoreCodeConstraint(sqlException.getMessage())) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static boolean mentionsStoreCodeConstraint(String message) {
    return message != null
        && message.toLowerCase(Locale.ROOT).contains("uk_store_tenant_code");
  }
}
