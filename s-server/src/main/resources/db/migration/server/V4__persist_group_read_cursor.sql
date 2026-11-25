-- Persist group read position and repair denormalized member counts.

DROP PROCEDURE IF EXISTS `schat_persist_group_read_cursor`;

DELIMITER //
CREATE PROCEDURE `schat_persist_group_read_cursor`()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'tb_group_member'
          AND column_name = 'last_read_seq'
    ) THEN
        ALTER TABLE `tb_group_member`
            ADD COLUMN `last_read_seq` bigint(20) NOT NULL DEFAULT 0
                COMMENT '成员已读到的群会话 seq' AFTER `muted`;
    END IF;

    UPDATE `tb_group_info` group_info
    SET group_info.`member_count` = (
        SELECT COUNT(*)
        FROM `tb_group_member` member
        WHERE member.`group_id` = group_info.`group_id`
    );
END//
DELIMITER ;

CALL `schat_persist_group_read_cursor`();

DROP PROCEDURE IF EXISTS `schat_persist_group_read_cursor`;
