ALTER TABLE tb_message_outbox ADD COLUMN delivery_revision int NOT NULL DEFAULT 0;
