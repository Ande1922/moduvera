DROP PROCEDURE IF EXISTS moduvera_messaging_assert_tenant_id_length;

DELIMITER $$
CREATE PROCEDURE moduvera_messaging_assert_tenant_id_length()
BEGIN
    IF EXISTS (
        SELECT tenant_id FROM moduvera_message_outbox WHERE CHAR_LENGTH(tenant_id) > 64
        UNION ALL
        SELECT tenant_id FROM moduvera_message_inbox WHERE CHAR_LENGTH(tenant_id) > 64
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'messaging tenant_id exceeds 64 characters; refusing to narrow persistence contract';
    END IF;
END$$
DELIMITER ;

CALL moduvera_messaging_assert_tenant_id_length();
DROP PROCEDURE moduvera_messaging_assert_tenant_id_length;

ALTER TABLE moduvera_message_outbox
    MODIFY tenant_id VARCHAR(64) NOT NULL;
ALTER TABLE moduvera_message_inbox
    MODIFY tenant_id VARCHAR(64) NOT NULL;
