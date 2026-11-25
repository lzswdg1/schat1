CREATE TABLE tb_conversation_sequence (
    conversation_key varchar(100) NOT NULL PRIMARY KEY,
    last_seq bigint NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tb_conversation_sequence (conversation_key, last_seq)
SELECT CONCAT('private:', conversation_low_id, ':', conversation_high_id), MAX(seq)
FROM tb_chat_message
GROUP BY conversation_low_id, conversation_high_id;

INSERT INTO tb_conversation_sequence (conversation_key, last_seq)
SELECT CONCAT('group:', group_id), MAX(seq)
FROM tb_group_message GROUP BY group_id;

CREATE TABLE tb_message_outbox (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    message_id bigint NOT NULL,
    target_user_id bigint NOT NULL,
    payload longtext NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    attempts int NOT NULL DEFAULT 0,
    available_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_until datetime(3) NULL,
    claim_token varchar(36) NULL,
    last_error varchar(500) NULL,
    created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at datetime(3) NULL,
    UNIQUE KEY uk_outbox_message_target (message_id, target_user_id),
    KEY idx_outbox_pending (status, available_at),
    KEY idx_outbox_lease (status, locked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
