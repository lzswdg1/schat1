-- Upgrade databases that were created from the legacy 1.sql before Flyway existed.

CREATE TABLE IF NOT EXISTS `tb_message_consume_log` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `message_id` bigint(20) NOT NULL COMMENT '消息ID',
    `message_type` varchar(32) NOT NULL COMMENT '消息类型：PRIVATE/GROUP',
    `consumer_group` varchar(128) NOT NULL COMMENT '消费者组',
    `status` varchar(32) NOT NULL COMMENT '消费状态：SUCCESS/DUPLICATE/FAILURE',
    `retry_count` int(11) NOT NULL DEFAULT '0' COMMENT '消费/更新次数',
    `fail_reason` varchar(512) DEFAULT NULL COMMENT '失败原因',
    `consume_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '最近消费时间',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_consumer` (`message_id`, `consumer_group`) COMMENT '同一消费组消费同一消息只保留一条状态',
    KEY `idx_status_time` (`status`, `consume_time`) COMMENT '按状态排查消费异常'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息消费状态日志表';

DROP PROCEDURE IF EXISTS `schat_upgrade_group_msg_idempotency`;

DELIMITER //
CREATE PROCEDURE `schat_upgrade_group_msg_idempotency`()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'tb_group_message'
          AND column_name = 'client_msg_id'
    ) THEN
        ALTER TABLE `tb_group_message`
            ADD COLUMN `client_msg_id` varchar(64) NULL COMMENT '客户端防重ID' AFTER `sender_id`;
    END IF;

    UPDATE `tb_group_message`
    SET `client_msg_id` = CONCAT('legacy-', `message_id`)
    WHERE `client_msg_id` IS NULL OR `client_msg_id` = '';

    UPDATE `tb_group_message` gm
    JOIN (
        SELECT `group_id`, `sender_id`, `client_msg_id`
        FROM `tb_group_message`
        GROUP BY `group_id`, `sender_id`, `client_msg_id`
        HAVING COUNT(*) > 1
    ) duplicated
      ON gm.`group_id` = duplicated.`group_id`
     AND gm.`sender_id` = duplicated.`sender_id`
     AND gm.`client_msg_id` = duplicated.`client_msg_id`
    SET gm.`client_msg_id` = CONCAT('legacy-', gm.`message_id`);

    ALTER TABLE `tb_group_message`
        MODIFY COLUMN `client_msg_id` varchar(64) NOT NULL COMMENT '客户端防重ID';

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'tb_group_message'
          AND index_name = 'uk_group_sender_client'
    ) THEN
        ALTER TABLE `tb_group_message`
            ADD UNIQUE KEY `uk_group_sender_client` (`group_id`, `sender_id`, `client_msg_id`);
    END IF;
END//
DELIMITER ;

CALL `schat_upgrade_group_msg_idempotency`();

DROP PROCEDURE IF EXISTS `schat_upgrade_group_msg_idempotency`;
