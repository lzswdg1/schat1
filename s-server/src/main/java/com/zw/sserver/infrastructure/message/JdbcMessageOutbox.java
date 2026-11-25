package com.zw.sserver.infrastructure.message;

import com.zw.sserver.domain.message.repository.MessageOutboxPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Repository
public class JdbcMessageOutbox implements MessageOutboxPort {

    private static final int MAX_ATTEMPTS = 12;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;

    public JdbcMessageOutbox(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public void enqueue(long messageId, long targetUserId, String payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox 必须与消息在同一事务内写入");
        }
        jdbcTemplate.update("""
                INSERT INTO tb_message_outbox (message_id, target_user_id, payload) VALUES (?, ?, ?)
                """, messageId, targetUserId, payload);
    }

    public List<Entry> claim(int limit) {
        return transactions.execute(status -> {
            List<Entry> entries = jdbcTemplate.query("""
                    SELECT id, target_user_id, payload, attempts, delivery_revision FROM tb_message_outbox
                    WHERE (
                        (status IN ('PENDING', 'AWAITING_ACK') AND available_at <= CURRENT_TIMESTAMP(3)) OR
                        (status = 'PROCESSING' AND locked_until < CURRENT_TIMESTAMP(3)))
                    ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                    """, (result, rowNumber) -> new Entry(result.getLong("id"),
                    result.getLong("target_user_id"), result.getString("payload"),
                    result.getInt("attempts") + 1, UUID.randomUUID().toString(), result.getInt("delivery_revision")), limit);
            List<Entry> claimed = new java.util.ArrayList<>();
            for (Entry entry : entries) {
                if (entry.attempts() > MAX_ATTEMPTS) {
                    jdbcTemplate.update("""
                            UPDATE tb_message_outbox SET status = 'FAILED', claim_token = NULL, locked_until = NULL,
                                last_error = 'Delivery attempts exhausted without acknowledgement'
                            WHERE id = ?
                            """, entry.id());
                    continue;
                }
                jdbcTemplate.update("""
                        UPDATE tb_message_outbox SET status = 'PROCESSING', attempts = attempts + 1,
                            claim_token = ?, locked_until = TIMESTAMPADD(SECOND, 30, CURRENT_TIMESTAMP(3))
                        WHERE id = ?
                        """, entry.claimToken(), entry.id());
                claimed.add(entry);
            }
            return claimed;
        });
    }

    public boolean complete(Entry entry) {
        return jdbcTemplate.update("""
                UPDATE tb_message_outbox SET status = CASE WHEN payload IS NULL THEN 'PUBLISHED' ELSE 'AWAITING_ACK' END,
                    published_at = CURRENT_TIMESTAMP(3), available_at = TIMESTAMPADD(SECOND, 10, CURRENT_TIMESTAMP(3)),
                    terminal_at = CASE WHEN payload IS NULL THEN CURRENT_TIMESTAMP(3) ELSE NULL END,
                    claim_token = NULL, locked_until = NULL, last_error = NULL
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, entry.id(), entry.claimToken()) == 1;
    }

    public void retry(Entry entry, String error) {
        int delaySeconds = Math.min(300, 1 << Math.min(entry.attempts(), 8));
        jdbcTemplate.update("""
                UPDATE tb_message_outbox SET status = ?, available_at = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3)),
                    claim_token = NULL, locked_until = NULL, last_error = ?
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, entry.attempts() >= MAX_ATTEMPTS ? "FAILED" : "PENDING", delaySeconds,
                error == null ? "Unknown delivery failure" : error.substring(0, Math.min(error.length(), 500)),
                entry.id(), entry.claimToken());
    }

    public boolean acknowledge(long messageId, long recipientId) {
        return acknowledge(messageId, recipientId, 0);
    }

    public boolean acknowledge(long messageId, long recipientId, int revision) {
        return jdbcTemplate.update("""
                UPDATE tb_message_outbox SET status = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP(3),
                    terminal_at = CURRENT_TIMESTAMP(3),
                    claim_token = NULL, locked_until = NULL, last_error = NULL
                WHERE message_id = ? AND target_user_id = ? AND payload IS NOT NULL
                    AND status NOT IN ('DELIVERED', 'CANCELLED') AND delivery_revision = ?
                """, messageId, recipientId, revision) == 1;
    }

    public boolean acceptsAcknowledgement(long messageId, long recipientId, int revision) {
        List<Boolean> matches = jdbcTemplate.query("""
                SELECT delivery_revision, status FROM tb_message_outbox
                WHERE message_id = ? AND target_user_id = ? FOR UPDATE
                """, (result, rowNumber) -> result.getInt("delivery_revision") == revision
                && !"CANCELLED".equals(result.getString("status")), messageId, recipientId);
        return matches.isEmpty() || matches.get(0);
    }

    public boolean isDeliverable(long deliveryId, long recipientId, int revision, long messageId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM tb_message_outbox WHERE id = ? AND target_user_id = ?
                    AND delivery_revision = ? AND message_id = ? AND payload IS NOT NULL AND status NOT IN ('CANCELLED', 'DELIVERED'))
                """, Boolean.class, deliveryId, recipientId, revision, messageId));
    }

    public boolean isCurrentMessage(long messageId, long recipientId, int revision) {
        List<Boolean> matches = jdbcTemplate.query("""
                SELECT delivery_revision, status FROM tb_message_outbox WHERE message_id = ? AND target_user_id = ?
                """, (result, rowNumber) -> result.getInt("delivery_revision") == revision
                && !"CANCELLED".equals(result.getString("status")), messageId, recipientId);
        return matches.isEmpty() || matches.get(0);
    }

    public void replaceWithRecall(long messageId, java.util.Collection<Long> recipients, String payload) {
        requireTransaction();
        jdbcTemplate.update("""
                UPDATE tb_message_outbox SET payload = ?, delivery_revision = delivery_revision + 1,
                    status = 'PENDING', attempts = 0, available_at = CURRENT_TIMESTAMP(3),
                    terminal_at = NULL,
                    claim_token = NULL, locked_until = NULL, delivered_at = NULL, last_error = NULL
                WHERE message_id = ? AND payload IS NOT NULL AND status <> 'CANCELLED'
                """, payload, messageId);
        for (Long recipient : recipients) {
            jdbcTemplate.update("""
                    INSERT INTO tb_message_outbox (message_id, target_user_id, payload, delivery_revision)
                    VALUES (?, ?, ?, 1) ON DUPLICATE KEY UPDATE id = id
                    """, messageId, recipient, payload);
        }
    }

    public void cancelGroup(long groupId) {
        requireTransaction();
        jdbcTemplate.update("""
                UPDATE tb_message_outbox outbox JOIN tb_group_message message ON outbox.message_id = message.message_id
                SET outbox.status = 'CANCELLED', outbox.payload = NULL,
                    outbox.terminal_at = CURRENT_TIMESTAMP(3),
                    outbox.delivery_revision = outbox.delivery_revision + 1,
                    outbox.claim_token = NULL, outbox.locked_until = NULL
                WHERE message.group_id = ?
                """, groupId);
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("消息生命周期必须在事务内更新");
        }
    }

    public void resumeForUser(long userId) {
        jdbcTemplate.update("""
                UPDATE tb_message_outbox SET attempts = CASE WHEN status = 'FAILED' OR attempts >= ? THEN 0 ELSE attempts END,
                    status = 'PENDING', available_at = CURRENT_TIMESTAMP(3), last_error = NULL, terminal_at = NULL
                WHERE target_user_id = ? AND payload IS NOT NULL AND status IN ('FAILED', 'PENDING', 'AWAITING_ACK')
                """, MAX_ATTEMPTS, userId);
    }

    public record Entry(long id, long targetUserId, String payload, int attempts, String claimToken, int revision) {
        public Entry(long id, long targetUserId, String payload, int attempts, String claimToken) {
            this(id, targetUserId, payload, attempts, claimToken, 0);
        }
    }
}
