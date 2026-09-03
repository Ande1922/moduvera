DROP PROCEDURE IF EXISTS moduvera_catalog_assert_tenant_id_length;

DELIMITER $$
CREATE PROCEDURE moduvera_catalog_assert_tenant_id_length()
BEGIN
    IF EXISTS (SELECT 1 FROM catalog_product WHERE CHAR_LENGTH(tenant_id) > 64) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'catalog tenant_id exceeds 64 characters; refusing to narrow persistence contract';
    END IF;
END$$
DELIMITER ;

CALL moduvera_catalog_assert_tenant_id_length();
DROP PROCEDURE moduvera_catalog_assert_tenant_id_length;

ALTER TABLE catalog_product
    MODIFY tenant_id VARCHAR(64) NOT NULL;
