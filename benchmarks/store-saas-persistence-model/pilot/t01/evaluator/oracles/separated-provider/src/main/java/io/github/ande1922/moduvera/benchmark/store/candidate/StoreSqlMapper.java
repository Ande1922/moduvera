package io.github.ande1922.moduvera.benchmark.store.candidate;

import org.apache.ibatis.annotations.Param;

public interface StoreSqlMapper {
  StoreRow selectById(
      @Param("tenantId") String tenantId, @Param("storeId") long storeId);

  int insert(StoreRow row);

  int updateWithVersion(
      @Param("row") StoreRow row, @Param("expectedVersion") long expectedVersion);
}
