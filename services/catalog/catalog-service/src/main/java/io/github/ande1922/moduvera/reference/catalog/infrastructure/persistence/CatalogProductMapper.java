package io.github.ande1922.moduvera.reference.catalog.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CatalogProductMapper {

    @Select("""
            SELECT tenant_id, product_id, name, unit_price, currency, version,
                   created_at, created_by, updated_at, updated_by
              FROM catalog_product
             WHERE tenant_id = #{tenantId}
               AND product_id = #{productId}
            """)
    CatalogProductRow findById(
            @Param("tenantId") String tenantId, @Param("productId") long productId);

    @Insert(value = """
            INSERT INTO catalog_product (
                tenant_id, product_id, name, unit_price, currency, version,
                created_at, created_by, updated_at, updated_by)
            VALUES (
                #{tenantId}, #{productId}, #{name}, #{unitPrice}, #{currency}, #{version},
                #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy})
            ON CONFLICT (tenant_id, product_id) DO UPDATE
               SET name = EXCLUDED.name,
                   unit_price = EXCLUDED.unit_price,
                   currency = EXCLUDED.currency,
                   version = EXCLUDED.version,
                   updated_at = EXCLUDED.updated_at,
                   updated_by = EXCLUDED.updated_by
            """, databaseId = "postgresql")
    @Insert(value = """
            INSERT INTO catalog_product (
                tenant_id, product_id, name, unit_price, currency, version,
                created_at, created_by, updated_at, updated_by)
            VALUES (
                #{tenantId}, #{productId}, #{name}, #{unitPrice}, #{currency}, #{version},
                #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy})
            ON DUPLICATE KEY UPDATE
                name = VALUES(name),
                unit_price = VALUES(unit_price),
                currency = VALUES(currency),
                version = VALUES(version),
                updated_at = VALUES(updated_at),
                updated_by = VALUES(updated_by)
            """, databaseId = "mysql")
    int save(CatalogProductRow row);
}
