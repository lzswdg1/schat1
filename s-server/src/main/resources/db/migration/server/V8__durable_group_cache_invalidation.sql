CREATE TABLE tb_group_cache_invalidation (
    group_id bigint NOT NULL PRIMARY KEY,
    revision bigint NOT NULL,
    published_revision bigint NOT NULL DEFAULT 0,
    published_at datetime(3) NULL,
    updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    next_attempt_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    failures int NOT NULL DEFAULT 0,
    last_error varchar(500) NULL,
    KEY idx_group_cache_retry (next_attempt_at),
    KEY idx_group_cache_reconcile (published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
