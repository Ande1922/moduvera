package io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderMapper {

    @Select("""
            SELECT tenant_id, order_id, status, currency, placed_at, version
              FROM order_header
             WHERE tenant_id = #{tenantId}
               AND order_id = #{orderId}
            """)
    OrderHeaderRow findHeader(@Param("tenantId") String tenantId, @Param("orderId") long orderId);

    @Select("""
            SELECT tenant_id, order_id, line_number, product_id, product_name, quantity, unit_price
              FROM order_line
             WHERE tenant_id = #{tenantId}
               AND order_id = #{orderId}
             ORDER BY line_number
            """)
    List<OrderLineRow> findLines(@Param("tenantId") String tenantId, @Param("orderId") long orderId);

    @Insert("""
            INSERT INTO order_header (
                tenant_id, order_id, status, currency, placed_at, version,
                created_at, created_by, updated_at, updated_by)
            VALUES (
                #{tenantId}, #{orderId}, #{status}, #{currency}, #{placedAt}, #{version},
                #{now}, #{actor}, #{now}, #{actor})
            """)
    int insertHeader(
            @Param("tenantId") String tenantId,
            @Param("orderId") long orderId,
            @Param("status") String status,
            @Param("currency") String currency,
            @Param("placedAt") Instant placedAt,
            @Param("version") long version,
            @Param("now") Instant now,
            @Param("actor") String actor);

    @Insert("""
            INSERT INTO order_line (
                tenant_id, order_id, line_number, product_id, product_name, quantity, unit_price)
            VALUES (
                #{tenantId}, #{orderId}, #{lineNumber}, #{productId},
                #{productName}, #{quantity}, #{unitPrice})
            """)
    int insertLine(OrderLineRow row);

    @Update("""
            UPDATE order_header
               SET status = #{status},
                   version = version + 1,
                   updated_at = #{now},
                   updated_by = #{actor}
             WHERE tenant_id = #{tenantId}
               AND order_id = #{orderId}
               AND version = #{expectedVersion}
            """)
    int updateStatus(
            @Param("tenantId") String tenantId,
            @Param("orderId") long orderId,
            @Param("status") String status,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now,
            @Param("actor") String actor);
}
