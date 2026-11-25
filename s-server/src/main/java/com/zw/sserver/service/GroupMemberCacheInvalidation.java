package com.zw.sserver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class GroupMemberCacheInvalidation {

    private static final Logger log = LoggerFactory.getLogger(GroupMemberCacheInvalidation.class);
    private static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[1]) or '0') or 0
            local incoming = tonumber(ARGV[1])
            if incoming > current then
                redis.call('SET', KEYS[1], ARGV[1])
                redis.call('DEL', KEYS[2])
                return 1
            end
            return 0
            """, Long.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final TransactionTemplate isolated;

    public GroupMemberCacheInvalidation(JdbcTemplate jdbc, StringRedisTemplate redis,
                                        PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.isolated = new TransactionTemplate(transactionManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void invalidate(long groupId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            isolated.executeWithoutResult(status -> invalidate(groupId));
            return;
        }
        jdbc.update("""
                INSERT INTO tb_group_cache_invalidation (group_id, revision) VALUES (?, 1)
                ON DUPLICATE KEY UPDATE revision = revision + 1, updated_at = CURRENT_TIMESTAMP(3),
                    next_attempt_at = CURRENT_TIMESTAMP(3), failures = 0, last_error = NULL
                """, groupId);
        Long revision = jdbc.queryForObject(
                "SELECT revision FROM tb_group_cache_invalidation WHERE group_id = ?", Long.class, groupId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    invalidateRedis(groupId, java.util.Objects.requireNonNull(revision));
                } catch (Exception exception) {
                    log.warn("群成员变更已提交，缓存快速失效失败，保留持久化重试任务: groupId={}", groupId, exception);
                }
            }
        });
    }

    public boolean publish(long groupId) {
        Long revision = null;
        try {
            revision = isolated.execute(status -> jdbc.queryForObject(
                    "SELECT revision FROM tb_group_cache_invalidation WHERE group_id = ?", Long.class, groupId));
            if (revision == null) return false;
            invalidateRedis(groupId, revision);
            long observed = revision;
            isolated.executeWithoutResult(status -> jdbc.update("""
                    UPDATE tb_group_cache_invalidation SET published_revision = ?, published_at = CURRENT_TIMESTAMP(3),
                        next_attempt_at = CURRENT_TIMESTAMP(3), failures = 0, last_error = NULL
                    WHERE group_id = ? AND revision = ?
                    """, observed, groupId, observed));
            return true;
        } catch (Exception exception) {
            Long observed = revision;
            if (observed != null) {
                try {
                    String error = String.valueOf(exception.getMessage());
                    isolated.executeWithoutResult(status -> jdbc.update("""
                            UPDATE tb_group_cache_invalidation SET failures = failures + 1,
                                next_attempt_at = TIMESTAMPADD(SECOND, LEAST(60, POW(2, LEAST(failures, 6))), CURRENT_TIMESTAMP(3)),
                                last_error = ? WHERE group_id = ? AND revision = ?
                            """, error.substring(0, Math.min(error.length(), 500)), groupId, observed));
                } catch (Exception trackingFailure) {
                    log.warn("群缓存失效任务状态更新失败: groupId={}", groupId, trackingFailure);
                }
            }
            log.warn("群成员变更已提交，缓存失效等待重试: groupId={}", groupId, exception);
            return false;
        }
    }

    private void invalidateRedis(long groupId, long revision) {
        Long published = redis.execute(INVALIDATE, List.of(revisionKey(groupId), membersKey(groupId)), String.valueOf(revision));
        if (published == null) throw new IllegalStateException("缓存失效未获得 Redis 确认");
    }

    @Scheduled(fixedDelayString = "${schat.group-cache.retry-interval-ms:1000}")
    public void retryPending() {
        List<Long> groups = jdbc.queryForList("""
                SELECT group_id FROM tb_group_cache_invalidation
                WHERE next_attempt_at <= CURRENT_TIMESTAMP(3) AND (
                    published_revision < revision OR published_at IS NULL OR
                    published_at <= TIMESTAMPADD(SECOND, -60, CURRENT_TIMESTAMP(3)))
                ORDER BY (published_revision < revision) DESC, published_at, group_id LIMIT 100
                """, Long.class);
        for (Long groupId : groups) {
            if (!publish(groupId)) break;
        }
    }

    static String membersKey(long groupId) {
        return "schat:group:members:v2:{" + groupId + "}";
    }

    static String revisionKey(long groupId) {
        return "schat:group:members:revision:{" + groupId + "}";
    }
}
