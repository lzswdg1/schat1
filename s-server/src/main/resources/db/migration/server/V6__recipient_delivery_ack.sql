ALTER TABLE tb_message_outbox
    ADD COLUMN delivered_at datetime(3) NULL,
    ADD KEY idx_outbox_recipient (target_user_id, status);

UPDATE tb_message_outbox
SET status = 'AWAITING_ACK', attempts = 0, available_at = CURRENT_TIMESTAMP(3)
WHERE status = 'PUBLISHED' AND payload IS NOT NULL;
