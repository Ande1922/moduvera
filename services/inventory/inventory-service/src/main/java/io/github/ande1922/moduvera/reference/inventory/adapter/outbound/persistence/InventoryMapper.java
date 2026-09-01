package io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryMapper {

    @Select("""
            SELECT command_id, order_id, result_type, unavailable_product_ids, decided_at
              FROM inventory_reservation_result
             WHERE tenant_id = #{tenantId}
               AND command_id = #{commandId}
            """)
    InventoryResultRow findResult(
            @Param("tenantId") String tenantId, @Param("commandId") String commandId);

    @Select("""
            <script>
            SELECT product_id, available, version
              FROM inventory_stock
             WHERE tenant_id = #{tenantId}
               AND product_id IN
               <foreach collection="productIds" item="productId" open="(" separator="," close=")">
                   #{productId}
               </foreach>
             ORDER BY product_id
             FOR UPDATE
            </script>
            """)
    List<InventoryStockRow> lockStocks(
            @Param("tenantId") String tenantId, @Param("productIds") List<Long> productIds);

    @Update("""
            UPDATE inventory_stock
               SET available = available - #{quantity},
                   version = version + 1,
                   updated_at = #{now},
                   updated_by = #{actor}
             WHERE tenant_id = #{tenantId}
               AND product_id = #{productId}
               AND version = #{expectedVersion}
               AND available >= #{quantity}
            """)
    int reserve(
            @Param("tenantId") String tenantId,
            @Param("productId") long productId,
            @Param("quantity") int quantity,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now,
            @Param("actor") String actor);

    @Insert(value = """
            INSERT INTO inventory_reservation_result (
                tenant_id, command_id, order_id, result_type,
                unavailable_product_ids, decided_at, created_by)
            VALUES (
                #{tenantId}, #{commandId}, #{orderId}, #{resultType},
                #{unavailableProductIds}, #{decidedAt}, #{actor})
            ON CONFLICT (tenant_id, command_id) DO NOTHING
            """, databaseId = "postgresql")
    @Insert(value = """
            INSERT IGNORE INTO inventory_reservation_result (
                tenant_id, command_id, order_id, result_type,
                unavailable_product_ids, decided_at, created_by)
            VALUES (
                #{tenantId}, #{commandId}, #{orderId}, #{resultType},
                #{unavailableProductIds}, #{decidedAt}, #{actor})
            """, databaseId = "mysql")
    int insertResult(
            @Param("tenantId") String tenantId,
            @Param("commandId") String commandId,
            @Param("orderId") long orderId,
            @Param("resultType") String resultType,
            @Param("unavailableProductIds") String unavailableProductIds,
            @Param("decidedAt") Instant decidedAt,
            @Param("actor") String actor);
}
