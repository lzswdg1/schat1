-- Make conversation sequence the authoritative, durable synchronization cursor.
-- Existing rows are deterministically backfilled before NOT NULL/unique constraints
-- are enabled. Redis allocation is floored by MAX(seq) in MySQL at runtime.

DROP PROCEDURE IF EXISTS `schat_enforce_conversation_sequence`;

DELIMITER //
CREATE PROCEDURE `schat_enforce_conversation_sequence`()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'tb_chat_message'
          AND column_name = 'seq'
    ) THEN
        ALTER TABLE `tb_chat_message`
            ADD COLUMN `seq` bigint(20) DEFAULT NULL COMMENT '会话级严格递增序号' AFTER `is_read`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'tb_group_message'
          AND column_name = 'seq'
    ) THEN
        ALTER TABLE `tb_group_message`
            ADD COLUMN `seq` bigint(20) DEFAULT NULL COMMENT '群会话级严格递增序号' AFTER `quote_content`;
    END IF;

    DROP TEMPORARY TABLE IF EXISTS `schat_private_seq_backfill`;
    CREATE TEMPORARY TABLE `schat_private_seq_backfill` (
        `message_id` bigint(20) NOT NULL PRIMARY KEY,
        `new_seq` bigint(20) NOT NULL
    );
    INSERT INTO `schat_private_seq_backfill` (`message_id`, `new_seq`)
    SELECT `message_id`,
           ROW_NUMBER() OVER (
               PARTITION BY LEAST(`sender_id`, `receiver_id`), GREATEST(`sender_id`, `receiver_id`)
               ORDER BY `send_time`, `message_id`
           )
    FROM `tb_chat_message`;
    UPDATE `tb_chat_message` message
    JOIN `schat_private_seq_backfill` backfill ON backfill.`message_id` = message.`message_id`
    SET message.`seq` = backfill.`new_seq`;
    DROP TEMPORARY TABLE `schat_private_seq_backfill`;

    DROP TEMPORARY TABLE IF EXISTS `schat_group_seq_backfill`;
    CREATE TEMPORARY TABLE `schat_group_seq_backfill` (
        `message_id` bigint(20) NOT NULL PRIMARY KEY,
        `new_seq` bigint(20) NOT NULL
    );
    INSERT INTO `schat_group_seq_backfill` (`message_id`, `new_seq`)
    SELECT `message_id`,
           ROW_NUMBER() OVER (
               PARTITION BY `group_id`
               ORDER BY `send_time`, `message_id`
           )
    FROM `tb_group_message`;
    UPDATE `tb_group_message` message
    JOIN `schat_group_seq_backfill` backfill ON backfill.`message_id` = message.`message_id`
    SET message.`seq` = backfill.`new_seq`;
    DROP TEMPORARY TABLE `schat_group_seq_backfill`;

    ALTER TABLE `tb_chat_message`
        MODIFY COLUMN `seq` bigint(20) NOT NULL COMMENT '会话级严格递增序号';
    ALTER TABLE `tb_group_message`
        MODIFY COLUMN `seq` bigint(20) NOT NULL COMMENT '群会话级严格递增序号';

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'tb_chat_message'
          AND column_name = 'conversation_low_id'
    ) THEN
        ALTER TABLE `tb_chat_message`
            ADD COLUMN `conversation_low_id` bigint(20)
                GENERATED ALWAYS AS (LEAST(`sender_id`, `receiver_id`)) STORED,
            ADD COLUMN `conversation_high_id` bigint(20)
                GENERATED ALWAYS AS (GREATEST(`sender_id`, `receiver_id`)) STORED;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'tb_chat_message'
          AND index_name = 'uk_private_conversation_seq'
    ) THEN
        ALTER TABLE `tb_chat_message`
            ADD UNIQUE KEY `uk_private_conversation_seq`
                (`conversation_low_id`, `conversation_high_id`, `seq`);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'tb_group_message'
          AND index_name = 'uk_group_seq'
    ) THEN
        ALTER TABLE `tb_group_message`
            ADD UNIQUE KEY `uk_group_seq` (`group_id`, `seq`);
    END IF;
END//
DELIMITER ;

CALL `schat_enforce_conversation_sequence`();

DROP PROCEDURE IF EXISTS `schat_enforce_conversation_sequence`;
