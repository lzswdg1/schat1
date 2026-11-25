package com.zw.sserver.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SCHAT_TEST_MYSQL_URL", matches = ".+")
class DeliveryOperationsIntegrationTest {

    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private DeliveryOperationsService service;

    @BeforeAll
    static void migrate() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("SCHAT_TEST_MYSQL_URL"), "root", "schat-test-only");
        try (var connection = source.getConnection()) {
            assertEquals("schat_test", connection.getCatalog());
        }
        Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(source).locations("classpath:db/migration/server").target("8").load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO tb_message_outbox (message_id, target_user_id, status, delivered_at, created_at)
                VALUES (1, 2, 'DELIVERED', '2026-01-02 00:00:00', '2026-01-01 00:00:00'),
                       (2, 2, 'PENDING', NULL, '2026-01-01 00:00:00')
                """);
        Flyway.configure().dataSource(source).locations("classpath:db/migration/server").load().migrate();
        assertEquals("2026-01-02T00:00", jdbc.queryForObject(
                "SELECT terminal_at FROM tb_message_outbox WHERE message_id = 1", java.sql.Timestamp.class).toLocalDateTime().toString());
        assertNull(jdbc.queryForObject("SELECT terminal_at FROM tb_message_outbox WHERE message_id = 2", java.sql.Timestamp.class));
        manager = new DataSourceTransactionManager(source);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM tb_delivery_ops_audit");
        jdbc.update("DELETE FROM tb_message_outbox");
        jdbc.update("DELETE FROM tb_group_cache_invalidation");
        service = new DeliveryOperationsService(jdbc, manager);
    }

    @Test
    void retryRequiresFailedStateAndExactRevisionAndKeepsCurrentPayload() {
        long failed = task(1, "FAILED", 0, "secret-body");
        long recall = task(2, "FAILED", 2, "recall-tombstone");
        long cancelled = task(3, "CANCELLED", 1, null);
        long delivered = task(4, "DELIVERED", 0, "delivered");
        var result = service.retry(List.of(item(failed, 0), item(recall, 1), item(cancelled, 1), item(delivered, 0)),
                "repair after route recovery", "tester", requestId());
        assertEquals(List.of("REQUEUED", "SKIPPED", "SKIPPED", "SKIPPED"), result.stream().map(DeliveryOperationsService.RetryOutcome::outcome).toList());
        assertEquals("PENDING", status(failed));
        assertEquals(0, jdbc.queryForObject("SELECT attempts FROM tb_message_outbox WHERE id = ?", Integer.class, failed));
        assertEquals("secret-body", jdbc.queryForObject("SELECT payload FROM tb_message_outbox WHERE id = ?", String.class, failed));
        assertEquals("FAILED", status(recall));
        service.retry(List.of(item(recall, 2)), "retry current tombstone only", "tester", requestId());
        assertEquals("recall-tombstone", jdbc.queryForObject("SELECT payload FROM tb_message_outbox WHERE id = ?", String.class, recall));
        assertEquals("CANCELLED", status(cancelled));
        assertEquals("DELIVERED", status(delivered));
        assertEquals(5, service.audit(0, 100).size());
    }

    @Test
    void failedPageUsesStableCursorAndNeverReturnsPayloadOrErrorText() throws Exception {
        long first = task(1, "FAILED", 0, "sensitive-body");
        task(2, "PENDING", 0, "not failed");
        long last = task(3, "FAILED", 1, "recall");
        var page = service.failed(0, 1);
        assertEquals(Long.toString(first), page.get(0).id());
        assertTrue(page.get(0).hasError());
        assertEquals(Long.toString(last), service.failed(first, 1).get(0).id());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(page);
        assertFalse(json.contains("sensitive"));
        assertFalse(json.contains("lastError"));
    }

    @Test
    void summaryIncludesFailedBacklogAgeAndPendingCacheWork() {
        task(1, "FAILED", 0, "{}");
        task(2, "AWAITING_ACK", 0, "{}");
        task(3, "DELIVERED", 0, "{}");
        jdbc.update("INSERT INTO tb_group_cache_invalidation (group_id, revision, published_revision, failures) VALUES (1, 2, 1, 3), (2, 1, 1, 0), (3, 1, 1, 1)");
        jdbc.update("UPDATE tb_message_outbox SET created_at = TIMESTAMPADD(SECOND, -600, CURRENT_TIMESTAMP(3))");
        var snapshot = service.summary();
        assertEquals(1, snapshot.failedCount());
        assertEquals(2, snapshot.unconfirmedCount());
        assertTrue(snapshot.oldestUnconfirmedSeconds() >= 600);
        assertEquals(1, snapshot.pendingCacheInvalidations());
        assertEquals(2, snapshot.cacheInvalidationErrors());
    }

    @Test
    void auditInsertFailureRollsBackRetry() {
        long failed = task(1, "FAILED", 0, "{}");
        long second = task(2, "FAILED", 0, "{}");
        rejectAudit(second);
        try {
            assertThrows(DataAccessException.class, () -> service.retry(List.of(item(failed, 0), item(second, 0)), "repair", "tester", requestId()));
            assertEquals("FAILED", status(failed));
            assertEquals("FAILED", status(second));
            assertEquals(12, jdbc.queryForObject("SELECT attempts FROM tb_message_outbox WHERE id = ?", Integer.class, failed));
            assertTrue(service.audit(0, 10).isEmpty());
        } finally {
            jdbc.execute("DROP TRIGGER reject_delivery_audit");
        }
    }

    @Test
    void concurrentRetryRequeuesOnlyOnceButAuditsBothAttempts() throws Exception {
        long failed = task(1, "FAILED", 0, "{}");
        var workers = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        java.util.concurrent.Callable<String> retry = () -> {
            start.await();
            return service.retry(List.of(item(failed, 0)), "repair", "tester", requestId()).get(0).outcome();
        };
        try {
            var first = workers.submit(retry);
            var second = workers.submit(retry);
            start.countDown();
            assertEquals(java.util.Set.of("REQUEUED", "SKIPPED"), java.util.Set.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)));
            assertEquals(2, service.audit(0, 10).size());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationWinsAgainstOperatorHoldingAnOldRevision() {
        long failed = task(1, "FAILED", 0, "{}");
        jdbc.update("UPDATE tb_message_outbox SET status = 'CANCELLED', payload = NULL, delivery_revision = 1 WHERE id = ?", failed);
        assertEquals("SKIPPED", service.retry(List.of(item(failed, 0)), "stale operator view", "tester", requestId()).get(0).outcome());
        assertEquals("CANCELLED", status(failed));
    }

    @Test
    void cleanupOnlyDeletesOldUnrevisedTerminalRowsAndAuditsEachDeletion() {
        long delivered = task(1, "DELIVERED", 0, "{}");
        long cache = task(2, "PUBLISHED", 0, null);
        task(3, "DELIVERED", 1, "tombstone");
        task(4, "CANCELLED", 0, null);
        task(5, "FAILED", 0, "{}");
        task(6, "PUBLISHED", 0, "legacy payload");
        long recent = task(7, "DELIVERED", 0, "{}");
        jdbc.update("UPDATE tb_message_outbox SET terminal_at = TIMESTAMPADD(DAY, -40, CURRENT_TIMESTAMP(3)) WHERE id <> ?", recent);
        assertEquals(2, service.cleanupPreview(30));
        assertEquals(1, service.cleanup(30, 1, "retention batch", "tester", requestId()));
        assertEquals(1, service.cleanup(30, 100, "retention batch", "tester", requestId()));
        assertEquals(0, service.cleanupPreview(30));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM tb_message_outbox", Integer.class));
        assertEquals(List.of(Long.toString(delivered), Long.toString(cache)), service.audit(0, 100).stream().map(DeliveryOperationsService.AuditEntry::taskId).toList());
    }

    @Test
    void auditFailureRollsBackCleanup() {
        task(1, "DELIVERED", 0, "{}");
        long second = task(2, "DELIVERED", 0, "{}");
        jdbc.update("UPDATE tb_message_outbox SET terminal_at = TIMESTAMPADD(DAY, -40, CURRENT_TIMESTAMP(3))");
        rejectAudit(second);
        try {
            assertThrows(DataAccessException.class, () -> service.cleanup(30, 10, "retention", "tester", requestId()));
            assertEquals(2, service.cleanupPreview(30));
            assertTrue(service.audit(0, 10).isEmpty());
        } finally {
            jdbc.execute("DROP TRIGGER reject_delivery_audit");
        }
    }

    @Test
    void cleanupSkipsRowsLockedByDeliveryLifecycleChanges() throws Exception {
        long locked = task(1, "DELIVERED", 0, "{}");
        task(2, "DELIVERED", 0, "{}");
        jdbc.update("UPDATE tb_message_outbox SET terminal_at = TIMESTAMPADD(DAY, -40, CURRENT_TIMESTAMP(3))");
        var worker = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(manager).executeWithoutResult(transaction -> {
                jdbc.queryForObject("SELECT id FROM tb_message_outbox WHERE id = ? FOR UPDATE", Long.class, locked);
                try {
                    assertEquals(1, worker.submit(() -> service.cleanup(30, 10, "retention", "tester", requestId())).get(5, TimeUnit.SECONDS));
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            assertEquals("DELIVERED", status(locked));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidBatchIsRejectedBeforeAnyMutation() {
        long failed = task(1, "FAILED", 0, "{}");
        assertThrows(IllegalArgumentException.class, () -> service.retry(List.of(item(failed, 0), item(failed, 0)), "repair", "tester", requestId()));
        assertThrows(IllegalArgumentException.class, () -> service.retry(List.of(item(failed, 0)), " ", "tester", requestId()));
        assertThrows(IllegalArgumentException.class, () -> service.failed(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> service.audit(0, 101));
        assertThrows(IllegalArgumentException.class, () -> service.cleanup(0, 10, "retention", "tester", requestId()));
        assertThrows(IllegalArgumentException.class, () -> service.cleanup(30, 1001, "retention", "tester", requestId()));
        assertEquals("FAILED", status(failed));
        assertTrue(service.audit(0, 10).isEmpty());
    }

    @Test
    void retentionClockStartsAtAcknowledgementAndResetsOnRecall() {
        long delivery = task(1, "PROCESSING", 0, "{}");
        long cache = task(2, "PROCESSING", 0, null);
        jdbc.update("UPDATE tb_message_outbox SET claim_token = 'claim', terminal_at = NULL, created_at = TIMESTAMPADD(DAY, -40, CURRENT_TIMESTAMP(3))");
        var outbox = new com.zw.sserver.infrastructure.message.JdbcMessageOutbox(jdbc, manager);
        assertTrue(outbox.complete(new com.zw.sserver.infrastructure.message.JdbcMessageOutbox.Entry(delivery, 2, "{}", 1, "claim", 0)));
        assertTrue(outbox.complete(new com.zw.sserver.infrastructure.message.JdbcMessageOutbox.Entry(cache, 2, null, 1, "claim", 0)));
        assertNull(jdbc.queryForObject("SELECT terminal_at FROM tb_message_outbox WHERE id = ?", java.sql.Timestamp.class, delivery));
        assertNotNull(jdbc.queryForObject("SELECT terminal_at FROM tb_message_outbox WHERE id = ?", java.sql.Timestamp.class, cache));
        assertTrue(outbox.acknowledge(1, 2, 0));
        assertNotNull(jdbc.queryForObject("SELECT terminal_at FROM tb_message_outbox WHERE id = ?", java.sql.Timestamp.class, delivery));
        assertEquals(0, service.cleanupPreview(30));
        new TransactionTemplate(manager).executeWithoutResult(transaction -> outbox.replaceWithRecall(1, List.of(2L), "tombstone"));
        assertNull(jdbc.queryForObject("SELECT terminal_at FROM tb_message_outbox WHERE id = ?", java.sql.Timestamp.class, delivery));
        assertEquals("PENDING", status(delivery));
    }

    private long task(long messageId, String status, int revision, String payload) {
        jdbc.update("""
                INSERT INTO tb_message_outbox (message_id, target_user_id, status, delivery_revision, payload, attempts, last_error, terminal_at)
                VALUES (?, 2, ?, ?, ?, 12, 'sensitive parser error containing body', CURRENT_TIMESTAMP(3))
                """, messageId, status, revision, payload);
        return jdbc.queryForObject("SELECT id FROM tb_message_outbox WHERE message_id = ?", Long.class, messageId);
    }

    private String status(long identifier) {
        return jdbc.queryForObject("SELECT status FROM tb_message_outbox WHERE id = ?", String.class, identifier);
    }

    private void rejectAudit(long taskId) {
        jdbc.execute("CREATE TRIGGER reject_delivery_audit BEFORE INSERT ON tb_delivery_ops_audit FOR EACH ROW BEGIN IF NEW.task_id = "
                + taskId + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit unavailable'; END IF; END");
    }

    private static DeliveryOperationsService.RetryItem item(long identifier, int revision) {
        return new DeliveryOperationsService.RetryItem(Long.toString(identifier), revision);
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }
}
