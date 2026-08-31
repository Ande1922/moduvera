package com.gaopc.benchmark.store.candidate;

import org.apache.ibatis.annotations.Param;

interface StoreRowMapper {
  StoreRow selectByTenantAndId(
      @Param("tenantId") String tenantId, @Param("storeId") long storeId);

  int insert(StoreRow row);

  int updateByTenantIdAndVersion(StoreRow row);
}
