package com.gaopc.benchmark.store.candidate;

import com.gaopc.benchmark.store.shared.ActorType;
import com.gaopc.benchmark.store.shared.Store;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;

public interface CandidateStoreMapper {
  Store findById(@Param("tenantId") String tenantId, @Param("storeId") long storeId);

  int insert(
      @Param("tenantId") String tenantId,
      @Param("store") Store store,
      @Param("actorType") ActorType actorType,
      @Param("actorId") String actorId,
      @Param("auditTime") Instant auditTime);

  int updateByVersion(
      @Param("tenantId") String tenantId,
      @Param("store") Store store,
      @Param("actorType") ActorType actorType,
      @Param("actorId") String actorId,
      @Param("auditTime") Instant auditTime);
}
