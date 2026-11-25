ALTER TABLE tb_message_outbox
    ADD COLUMN updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    ADD COLUMN terminal_at datetime(3) NULL,
    ADD KEY idx_outbox_failed_page (status, id),
    ADD KEY idx_outbox_retention (status, terminal_at);

UPDATE tb_message_outbox
SET terminal_at = COALESCE(delivered_at, published_at, created_at)
WHERE status IN ('DELIVERED', 'PUBLISHED', 'CANCELLED');

CREATE TABLE tb_delivery_ops_audit (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id varchar(36) NOT NULL,
    actor_label varchar(64) NOT NULL,
    operation varchar(32) NOT NULL,
    task_id bigint NOT NULL,
    expected_revision int NOT NULL,
    outcome varchar(16) NOT NULL,
    reason varchar(200) NOT NULL,
    created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_delivery_audit_request (request_id),
    KEY idx_delivery_audit_task (task_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
