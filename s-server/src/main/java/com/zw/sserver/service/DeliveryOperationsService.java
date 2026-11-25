package com.zw.sserver.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeliveryOperationsService {

    private static final String RETAINABLE = """
            delivery_revision = 0 AND terminal_at < TIMESTAMPADD(DAY, -?, CURRENT_TIMESTAMP(3))
            AND (status = 'DELIVERED' OR (status = 'PUBLISHED' AND payload IS NULL))
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public DeliveryOperationsService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Snapshot summary() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT status, COUNT(*) AS total FROM tb_message_outbox GROUP BY status", result -> {
            counts.put(result.getString("status"), result.getLong("total"));
        });
        var backlog = jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       COALESCE(GREATEST(0, TIMESTAMPDIFF(SECOND, MIN(created_at), CURRENT_TIMESTAMP(3))), 0) AS age
                FROM tb_message_outbox WHERE status IN ('PENDING', 'PROCESSING', 'AWAITING_ACK', 'FAILED')
                """, (result, row) -> new long[]{result.getLong("total"), result.getLong("age")});
        var cache = jdbc.queryForObject("""
                SELECT COALESCE(SUM(revision > published_revision), 0) AS total,
                       COALESCE(SUM(failures > 0), 0) AS errors
                FROM tb_group_cache_invalidation
                """, (result, row) -> new long[]{result.getLong("total"), result.getLong("errors")});
        return new Snapshot(Map.copyOf(counts), counts.getOrDefault("FAILED", 0L), backlog[0], backlog[1], cache[0], cache[1]);
    }

    public List<FailedTask> failed(long afterId, int limit) {
        validatePage(afterId, limit);
        return jdbc.query("""
                SELECT id, message_id, target_user_id, delivery_revision, attempts, created_at, updated_at,
                       last_error IS NOT NULL AS has_error
                FROM tb_message_outbox WHERE status = 'FAILED' AND id > ? ORDER BY id LIMIT ?
                """, (result, row) -> new FailedTask(result.getString("id"), result.getString("message_id"),
                result.getString("target_user_id"), result.getInt("delivery_revision"), result.getInt("attempts"),
                result.getTimestamp("created_at").toLocalDateTime(), result.getTimestamp("updated_at").toLocalDateTime(),
                result.getBoolean("has_error")), afterId, limit);
    }

    public List<AuditEntry> audit(long afterId, int limit) {
        validatePage(afterId, limit);
        return jdbc.query("""
                SELECT id, request_id, actor_label, operation, task_id, expected_revision, outcome, reason, created_at
                FROM tb_delivery_ops_audit WHERE id > ? ORDER BY id LIMIT ?
                """, (result, row) -> new AuditEntry(result.getString("id"), result.getString("request_id"),
                result.getString("actor_label"), result.getString("operation"), result.getString("task_id"),
                result.getInt("expected_revision"), result.getString("outcome"), result.getString("reason"),
                result.getTimestamp("created_at").toLocalDateTime()), afterId, limit);
    }

    public List<RetryOutcome> retry(List<RetryItem> items, String reason, String actor, String requestId) {
        validateAudit(reason, actor, requestId);
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new IllegalArgumentException("每次重试必须指定 1 至 100 个任务");
        }
        var identifiers = new HashSet<Long>();
        for (RetryItem item : items) {
            if (item == null || item.revision() == null || item.revision() < 0
                    || !identifiers.add(parseId(item.id()))) {
                throw new IllegalArgumentException("任务 ID 不能重复，版本必须为非负整数");
            }
        }
        var ordered = items.stream().sorted(Comparator.comparingLong(item -> parseId(item.id()))).toList();
        return transactions.execute(status -> {
            List<RetryOutcome> outcomes = new ArrayList<>();
            for (RetryItem item : ordered) {
                long taskId = parseId(item.id());
                int changed = jdbc.update("""
                        UPDATE tb_message_outbox SET status = 'PENDING', attempts = 0,
                            available_at = CURRENT_TIMESTAMP(3), claim_token = NULL, locked_until = NULL,
                            last_error = NULL, terminal_at = NULL
                        WHERE id = ? AND status = 'FAILED' AND delivery_revision = ?
                        """, taskId, item.revision());
                String outcome = changed == 1 ? "REQUEUED" : "SKIPPED";
                writeAudit(requestId, actor, "RETRY", taskId, item.revision(), outcome, reason);
                outcomes.add(new RetryOutcome(Long.toString(taskId), outcome));
            }
            return List.copyOf(outcomes);
        });
    }

    public long cleanupPreview(int olderThanDays) {
        validateRetention(olderThanDays);
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_message_outbox WHERE " + RETAINABLE, Long.class, olderThanDays);
    }

    public int cleanup(int olderThanDays, int limit, String reason, String actor, String requestId) {
        validateRetention(olderThanDays);
        validateAudit(reason, actor, requestId);
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("清理批次必须在 1 至 1000 之间");
        }
        return transactions.execute(status -> {
            var identifiers = jdbc.queryForList("SELECT id FROM tb_message_outbox WHERE " + RETAINABLE
                    + " ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED", Long.class, olderThanDays, limit);
            for (long taskId : identifiers) {
                writeAudit(requestId, actor, "CLEANUP", taskId, 0, "DELETED", reason);
                jdbc.update("DELETE FROM tb_message_outbox WHERE id = ?", taskId);
            }
            return identifiers.size();
        });
    }

    private void writeAudit(String requestId, String actor, String operation, long taskId, int revision,
                            String outcome, String reason) {
        jdbc.update("""
                INSERT INTO tb_delivery_ops_audit
                    (request_id, actor_label, operation, task_id, expected_revision, outcome, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, requestId, actor, operation, taskId, revision, outcome, reason);
    }

    private static void validatePage(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("游标必须为非负整数，分页大小必须在 1 至 100 之间");
        }
    }

    private static void validateRetention(int days) {
        if (days < 1 || days > 3650) {
            throw new IllegalArgumentException("保留天数必须在 1 至 3650 之间");
        }
    }

    private static void validateAudit(String reason, String actor, String requestId) {
        if (reason == null || reason.isBlank() || reason.length() > 200 || reason.chars().anyMatch(Character::isISOControl)
                || actor == null || !actor.matches("[A-Za-z0-9_.:@-]{1,64}")
                || requestId == null || !requestId.matches("[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("必须提供有效的操作原因、操作人和请求 ID");
        }
    }

    private static long parseId(String identifier) {
        if (identifier == null || !identifier.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("任务 ID 必须为正整数的字符串");
        }
        try {
            return Long.parseLong(identifier);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("任务 ID 超出范围");
        }
    }

    public record Snapshot(Map<String, Long> statusCounts, long failedCount, long unconfirmedCount,
                           long oldestUnconfirmedSeconds, long pendingCacheInvalidations, long cacheInvalidationErrors) { }
    public record FailedTask(String id, String messageId, String targetUserId, int revision, int attempts,
                             LocalDateTime createdAt, LocalDateTime updatedAt, boolean hasError) { }
    public record RetryItem(String id, Integer revision) { }
    public record RetryOutcome(String id, String outcome) { }
    public record AuditEntry(String id, String requestId, String actorLabel, String operation, String taskId,
                             int expectedRevision, String outcome, String reason, LocalDateTime createdAt) { }
}
