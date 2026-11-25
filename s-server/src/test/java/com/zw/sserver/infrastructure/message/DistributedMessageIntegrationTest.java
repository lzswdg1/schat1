package com.zw.sserver.infrastructure.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.application.message.SendPrivateMessageCommand;
import com.zw.sserver.application.message.MessageSendAdmission;
import com.zw.sserver.application.message.SendPrivateMessageUseCase;
import com.zw.sserver.application.message.SendMessageResult;
import com.zw.sserver.application.message.SendGroupMessageCommand;
import com.zw.sserver.application.message.SendGroupMessageUseCase;
import com.zw.sserver.application.session.SessionCacheInvalidationService;
import com.zw.sserver.domain.message.repository.MessageOutboxPort;
import com.zw.sserver.domain.message.repository.PrivateMessagePersistencePort;
import com.zw.sserver.domain.message.repository.GroupMessagePersistencePort;
import com.zw.sserver.domain.message.service.ConversationSequencePort;
import com.zw.sserver.domain.message.service.MessageDeliveryPort;
import com.zw.sserver.domain.message.service.PrivateMessagePolicy;
import com.zw.sserver.domain.message.service.GroupMembershipPort;
import com.zw.sserver.domain.message.service.GroupMessagePolicy;
import com.zw.sserver.model.dto.ChatMsgDTO;
import com.zw.sserver.model.dto.GroupMsgDTO;
import com.zw.sserver.service.ConversationSeqService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SCHAT_TEST_MYSQL_URL", matches = ".+")
class DistributedMessageIntegrationTest {

    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static TransactionTemplate transactions;
    private static PrivateMessagePersistencePort privatePersistence;
    private static GroupMessagePersistencePort groupPersistence;
    private static com.zw.sserver.mapper.ChatMessageMapper chatMapper;
    private static com.zw.sserver.mapper.GroupMessageMapper groupMessageMapper;
    private static com.zw.sserver.mapper.GroupInfoMapper groupInfoMapper;
    private static com.zw.sserver.mapper.GroupMemberMapper groupMemberMapper;
    private JdbcMessageOutbox outbox;
    private ExecutorService workers;

    @BeforeAll
    static void migrate() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("SCHAT_TEST_MYSQL_URL"), "root", "schat-test-only");
        try (var connection = source.getConnection()) {
            assertEquals("schat_test", connection.getCatalog());
        }
        Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        Flyway.configure().dataSource(source).locations("classpath:db/migration/server").target("4").load().migrate();
        jdbc.update("""
                INSERT INTO tb_chat_message (message_id, client_msg_id, sender_id, receiver_id, cipher_content, seq)
                VALUES (1, 'legacy', 1, 2, '{}', 41)
                """);
        jdbc.update("""
                INSERT INTO tb_group_message (message_id, client_msg_id, sender_id, group_id, cipher_content, seq)
                VALUES (2, 'legacy-group', 1, 10, '{}', 9)
                """);
        Flyway.configure().dataSource(source).locations("classpath:db/migration/server").target("5").load().migrate();
        jdbc.update("INSERT INTO tb_message_outbox (message_id, target_user_id, payload, status) VALUES (1, 2, '{}', 'PUBLISHED')");
        jdbc.update("INSERT INTO tb_message_outbox (message_id, target_user_id, payload, status) VALUES (1, 1, NULL, 'PUBLISHED')");
        Flyway.configure().dataSource(source).locations("classpath:db/migration/server").load().migrate();
        assertEquals("AWAITING_ACK", jdbc.queryForObject("SELECT status FROM tb_message_outbox WHERE target_user_id = 2", String.class));
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM tb_message_outbox WHERE target_user_id = 1", String.class));
        assertEquals(41L, jdbc.queryForObject("SELECT last_seq FROM tb_conversation_sequence WHERE conversation_key = 'private:1:2'", Long.class));
        assertEquals(9L, jdbc.queryForObject("SELECT last_seq FROM tb_conversation_sequence WHERE conversation_key = 'group:10'", Long.class));
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(com.zw.sserver.mapper.ChatMessageMapper.class);
        configuration.addMapper(com.zw.sserver.mapper.GroupMessageMapper.class);
        configuration.addMapper(com.zw.sserver.mapper.GroupInfoMapper.class);
        configuration.addMapper(com.zw.sserver.mapper.GroupMemberMapper.class);
        var mapperFactory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        mapperFactory.setDataSource(source);
        mapperFactory.setConfiguration(configuration);
        var sessions = new org.mybatis.spring.SqlSessionTemplate(java.util.Objects.requireNonNull(mapperFactory.getObject()));
        chatMapper = sessions.getMapper(com.zw.sserver.mapper.ChatMessageMapper.class);
        privatePersistence = new MybatisPrivateMessagePersistenceAdapter(chatMapper);
        groupPersistence = new MybatisGroupMessagePersistenceAdapter(sessions.getMapper(com.zw.sserver.mapper.GroupMessageMapper.class));
        groupMessageMapper = sessions.getMapper(com.zw.sserver.mapper.GroupMessageMapper.class);
        groupInfoMapper = sessions.getMapper(com.zw.sserver.mapper.GroupInfoMapper.class);
        groupMemberMapper = sessions.getMapper(com.zw.sserver.mapper.GroupMemberMapper.class);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM tb_message_outbox");
        jdbc.update("DELETE FROM tb_chat_message");
        jdbc.update("DELETE FROM tb_group_message");
        jdbc.update("DELETE FROM tb_group_member");
        jdbc.update("DELETE FROM tb_group_info");
        jdbc.update("DELETE FROM tb_conversation_sequence");
        outbox = new JdbcMessageOutbox(jdbc, manager);
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopWorkers() throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    void slowerLowerSequenceMustCommitBeforeHigherSequenceCanBeAllocated() throws Exception {
        var allocated = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstNode = useCase(outbox, delayedSequence(allocated, release));
        var secondNode = useCase(outbox, sequence());
        Future<SendMessageResult> first = workers.submit(() -> firstNode.execute(command("first", 2L)));
        assertTrue(allocated.await(5, TimeUnit.SECONDS));
        Future<SendMessageResult> second = workers.submit(() -> secondNode.execute(command("second", 2L)));
        try {
            assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));
            assertEquals(0, count("tb_chat_message"));
        } finally {
            release.countDown();
        }
        assertEquals(1L, first.get(10, TimeUnit.SECONDS).getSeq());
        assertEquals(2L, second.get(10, TimeUnit.SECONDS).getSeq());
        assertEquals(List.of(1L, 2L), jdbc.queryForList("SELECT seq FROM tb_chat_message ORDER BY seq", Long.class));
        assertEquals(4, count("tb_message_outbox"));
    }

    @Test
    void unrelatedConversationDoesNotWaitForLockedConversation() throws Exception {
        var allocated = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Future<SendMessageResult> first = workers.submit(() -> useCase(outbox, delayedSequence(allocated, release))
                .execute(command("first", 2L)));
        assertTrue(allocated.await(5, TimeUnit.SECONDS));
        try {
            Future<SendMessageResult> other = workers.submit(() -> useCase(outbox, sequence()).execute(command("other", 3L)));
            assertTrue(other.get(5, TimeUnit.SECONDS).isSuccess());
        } finally {
            release.countDown();
        }
        assertTrue(first.get(10, TimeUnit.SECONDS).isSuccess());
    }

    @Test
    void outboxFailureRollsBackMessageSequenceAndAnyPartialEvents() {
        MessageOutboxPort failingOutbox = (messageId, targetUserId, payload) -> {
            outbox.enqueue(messageId, targetUserId, payload);
            throw new IllegalStateException("simulated failure after enqueue");
        };
        assertFalse(useCase(failingOutbox, sequence()).execute(command("failed", 2L)).isSuccess());
        assertEquals(0, count("tb_chat_message"));
        assertEquals(0, count("tb_conversation_sequence"));
        assertEquals(0, count("tb_message_outbox"));
        assertEquals(1L, useCase(outbox, sequence()).execute(command("retry", 2L)).getSeq());
    }

    @Test
    void acknowledgedSendRemainsCommittedWhenAnOuterTransactionRollsBack() {
        transactions.executeWithoutResult(status -> {
            assertTrue(useCase(outbox, sequence()).execute(command("acknowledged", 2L)).isSuccess());
            status.setRollbackOnly();
        });
        assertEquals(1, count("tb_chat_message"));
        assertEquals(2, count("tb_message_outbox"));
    }

    @Test
    void springSequenceProxyRequiresAnExistingMessageTransaction() {
        var factory = new org.springframework.aop.framework.ProxyFactory(new ConversationSeqService(jdbc));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var service = (ConversationSeqService) factory.getProxy();
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> service.nextSeq(1L, 2L));
        assertEquals(0, count("tb_conversation_sequence"));
    }

    @Test
    void concurrentDuplicateOnlyCommitsOneMessageAndOneSequence() throws Exception {
        var start = new CountDownLatch(1);
        Callable<SendMessageResult> send = () -> {
            start.await();
            return useCase(outbox, sequence()).execute(command("same-client-id", 2L));
        };
        Future<SendMessageResult> first = workers.submit(send);
        Future<SendMessageResult> second = workers.submit(send);
        start.countDown();
        SendMessageResult firstResult = first.get(10, TimeUnit.SECONDS);
        SendMessageResult secondResult = second.get(10, TimeUnit.SECONDS);
        assertTrue(firstResult.isSuccess());
        assertTrue(secondResult.isSuccess());
        assertEquals(firstResult.getMessageId(), secondResult.getMessageId());
        assertEquals(1, count("tb_chat_message"));
        assertEquals(2, count("tb_message_outbox"));
        assertEquals(1L, jdbc.queryForObject("SELECT last_seq FROM tb_conversation_sequence", Long.class));
    }

    @Test
    void independentPublishersClaimDisjointBatches() throws Exception {
        transactions.executeWithoutResult(status -> {
            for (long number = 1; number <= 40; number++) outbox.enqueue(number, 2, "payload");
        });
        Future<List<JdbcMessageOutbox.Entry>> first = workers.submit(() -> outbox.claim(20));
        Future<List<JdbcMessageOutbox.Entry>> second = workers.submit(() -> new JdbcMessageOutbox(jdbc, manager).claim(20));
        var ids = new HashSet<Long>();
        for (var entry : first.get(10, TimeUnit.SECONDS)) assertTrue(ids.add(entry.id()));
        for (var entry : second.get(10, TimeUnit.SECONDS)) assertTrue(ids.add(entry.id()));
        assertEquals(40, ids.size());
    }

    @Test
    void publisherCannotObserveOutboxBeforeMessageTransactionCommits() throws Exception {
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Future<?> writer = workers.submit(() -> transactions.executeWithoutResult(status -> {
            outbox.enqueue(1, 2, "payload");
            inserted.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }));
        assertTrue(inserted.await(5, TimeUnit.SECONDS));
        try {
            Future<List<JdbcMessageOutbox.Entry>> reader = workers.submit(() -> outbox.claim(1));
            assertTrue(reader.get(5, TimeUnit.SECONDS).isEmpty());
        } finally {
            release.countDown();
        }
        writer.get(10, TimeUnit.SECONDS);
        assertEquals(1, outbox.claim(1).size());
    }

    @Test
    void groupRecipientFailureRollsBackAllRecipientsAndSequence() {
        GroupMembershipPort membership = mock(GroupMembershipPort.class);
        when(membership.isMember(10L, 1L)).thenReturn(true);
        when(membership.memberIds(10L)).thenReturn(Set.of(1L, 2L, 3L));
        when(membership.lockAndLoadMembers(10L, 1L)).thenReturn(Set.of(1L, 2L, 3L));
        MessageOutboxPort failing = (messageId, targetUserId, payload) -> {
            outbox.enqueue(messageId, targetUserId, payload);
            if (targetUserId == 3) throw new IllegalStateException("recipient enqueue failed");
        };
        GroupMsgDTO message = new GroupMsgDTO();
        message.setGroupId(10L);
        message.setClientMsgId("group-client");
        message.setMsgType("GROUP_TEXT");
        message.setContent("group message");
        var command = new SendGroupMessageCommand(1L, message);
        var failingUseCase = new SendGroupMessageUseCase(new ObjectMapper(), new GroupMessagePolicy(),
                membership, sequence(), failing, groupPersistence, manager, mock(MessageSendAdmission.class));
        assertFalse(failingUseCase.execute(command).isSuccess());
        assertEquals(0, count("tb_group_message"));
        assertEquals(0, count("tb_conversation_sequence"));
        assertEquals(0, count("tb_message_outbox"));
        var retry = new SendGroupMessageUseCase(new ObjectMapper(), new GroupMessagePolicy(),
                membership, sequence(), outbox, groupPersistence, manager, mock(MessageSendAdmission.class)).execute(command);
        assertTrue(retry.isSuccess());
        assertEquals(1L, retry.getSeq());
        assertEquals(3, count("tb_message_outbox"));
    }

    @Test
    void expiredClaimCanBeTakenOverAndOldWorkerCannotCompleteOrRetryIt() {
        transactions.executeWithoutResult(status -> outbox.enqueue(1, 2, "payload"));
        var oldClaim = outbox.claim(1).get(0);
        jdbc.update("UPDATE tb_message_outbox SET locked_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(3))");
        var recovered = new JdbcMessageOutbox(jdbc, manager).claim(1).get(0);
        assertEquals(2, recovered.attempts());
        assertNotEquals(oldClaim.claimToken(), recovered.claimToken());
        assertFalse(outbox.complete(oldClaim));
        outbox.retry(oldClaim, "stale failure");
        assertTrue(outbox.complete(recovered));
        assertEquals("AWAITING_ACK", jdbc.queryForObject("SELECT status FROM tb_message_outbox", String.class));
    }

    @Test
    void failedDeliveryRetriesAndExhaustionIsRetainedForInspection() {
        transactions.executeWithoutResult(status -> outbox.enqueue(1, 2, "{\"type\":\"NEW_MESSAGE\",\"data\":{\"id\":\"1\"}}"));
        var delivery = mock(MessageDeliveryPort.class);
        var publisher = new MessageOutboxPublisher(outbox, delivery, mock(SessionCacheInvalidationService.class), new DeliveryEnvelope(outbox, new ObjectMapper()), healthyRedis());
        publisher.publish();
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM tb_message_outbox", String.class));
        jdbc.update("UPDATE tb_message_outbox SET available_at = CURRENT_TIMESTAMP(3)");
        when(delivery.deliverPrivate(eq(2L), anyString())).thenReturn(true);
        publisher.publish();
        assertEquals("AWAITING_ACK", jdbc.queryForObject("SELECT status FROM tb_message_outbox", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT attempts FROM tb_message_outbox", Integer.class));
        transactions.executeWithoutResult(status -> outbox.enqueue(2, 2, "payload"));
        jdbc.update("UPDATE tb_message_outbox SET attempts = 11 WHERE status = 'PENDING'");
        var lastClaim = outbox.claim(1).get(0);
        outbox.retry(lastClaim, "unavailable");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_message_outbox WHERE status = 'FAILED'", Integer.class));
        assertTrue(outbox.claim(1).isEmpty());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void acknowledgementIsRecipientScopedIdempotentAndCannotBeOverwrittenByPublisher() {
        transactions.executeWithoutResult(status -> {
            outbox.enqueue(1, 2, "payload");
            outbox.enqueue(1, 1, null);
        });
        var claimed = outbox.claim(10);
        assertFalse(outbox.acknowledge(1, 999));
        assertFalse(outbox.acknowledge(1, 1));
        assertTrue(outbox.acknowledge(1, 2));
        assertFalse(outbox.acknowledge(1, 2));
        for (var entry : claimed) {
            outbox.complete(entry);
            outbox.retry(entry, "late failure");
        }
        assertEquals("DELIVERED", statusFor(2));
        assertEquals("PUBLISHED", statusFor(1));
        assertNotNull(jdbc.queryForObject("SELECT delivered_at FROM tb_message_outbox WHERE target_user_id = 2", java.sql.Timestamp.class));
    }

    @Test
    void groupRecipientsHaveIndependentAcknowledgementsAndLateAcknowledgementRepairsExhaustion() {
        transactions.executeWithoutResult(status -> {
            outbox.enqueue(1, 2, "group-payload");
            outbox.enqueue(1, 3, "group-payload");
        });
        for (var entry : outbox.claim(10)) outbox.complete(entry);
        assertTrue(outbox.acknowledge(1, 2));
        assertEquals("AWAITING_ACK", statusFor(3));
        jdbc.update("UPDATE tb_message_outbox SET attempts = 12, available_at = CURRENT_TIMESTAMP(3) WHERE target_user_id = 3");
        assertTrue(outbox.claim(10).isEmpty());
        assertEquals("FAILED", statusFor(3));
        assertTrue(outbox.acknowledge(1, 3));
        assertEquals("DELIVERED", statusFor(3));
        assertEquals("DELIVERED", statusFor(2));
    }

    @Test
    void reconnectRevivesOnlyUnacknowledgedRecipientsAndAckTimeoutSchedulesRedelivery() {
        transactions.executeWithoutResult(status -> {
            outbox.enqueue(1, 2, "payload");
            outbox.enqueue(2, 3, "other");
            outbox.enqueue(3, 4, "already received");
        });
        outbox.acknowledge(3, 4);
        for (var entry : outbox.claim(10)) outbox.complete(entry);
        assertTrue(outbox.claim(10).isEmpty());
        jdbc.update("UPDATE tb_message_outbox SET available_at = CURRENT_TIMESTAMP(3) WHERE target_user_id = 2");
        var retry = outbox.claim(10);
        assertEquals(1, retry.size());
        assertEquals(2L, retry.get(0).targetUserId());
        assertEquals(2, retry.get(0).attempts());
        outbox.retry(retry.get(0), "disconnected");
        jdbc.update("UPDATE tb_message_outbox SET status = 'FAILED', attempts = 12 WHERE target_user_id IN (2, 3)");
        outbox.resumeForUser(2);
        outbox.resumeForUser(4);
        var resumed = outbox.claim(10);
        assertEquals(1, resumed.size());
        assertEquals(1, resumed.get(0).attempts());
        assertEquals("FAILED", statusFor(3));
        assertEquals("DELIVERED", statusFor(4));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SCHAT_TEST_REDIS_PORT", matches = ".+")
    void lostNodeAndLostAckRecoverThroughTheNewConnectionWithoutDeliveringToTheOldOne() throws Exception {
        var connections = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                "127.0.0.1", Integer.parseInt(System.getenv("SCHAT_TEST_REDIS_PORT")));
        connections.afterPropertiesSet();
        connections.start();
        try {
            var redis = new org.springframework.data.redis.core.StringRedisTemplate(connections);
            redis.delete(List.of("schat:online:users", "schat:route:node-b:8082"));
            var online = new com.zw.sserver.registry.impl.RedisOnlineUserRegistry(redis);
            var instances = mock(com.zw.sserver.registry.InstanceRegistry.class);
            when(instances.getInstanceId()).thenReturn("node-a:8082");
            when(instances.isAlive("node-b:8082")).thenReturn(true);
            var sessions = mock(com.zw.sserver.websocket.LocalSessionManager.class);
            var oldSession = mock(jakarta.websocket.Session.class);
            when(oldSession.getUserProperties()).thenReturn(new java.util.HashMap<>(java.util.Map.of("connectionId", "old-a")));
            when(sessions.getSession(2L)).thenReturn(oldSession);
            when(sessions.sendToUser(eq(2L), anyString())).thenReturn(true);
            online.userOnline(2L, "node-b:8082", "connection-b");
            var router = new com.zw.sserver.router.impl.RedisCrossNodeRouter(sessions, online, instances, redis);
            var delivery = new CrossNodeMessageDeliveryAdapter(router, instances);
            var publisher = new MessageOutboxPublisher(outbox, delivery, mock(SessionCacheInvalidationService.class), new DeliveryEnvelope(outbox, new ObjectMapper()), redis);
            var sent = useCase(outbox, sequence()).execute(command("recover", 2L));
            assertTrue(sent.isSuccess());
            publisher.publish();
            assertEquals("AWAITING_ACK", statusFor(2));
            assertEquals(1L, redis.opsForStream().size("schat:route:node-b:8082"));
            verify(sessions, never()).sendToUser(anyLong(), anyString());

            when(instances.isAlive("node-b:8082")).thenReturn(false);
            online.userOnline(2L, "node-a:8082", "replacement");
            var replacement = mock(jakarta.websocket.Session.class);
            when(replacement.getUserProperties()).thenReturn(new java.util.HashMap<>(java.util.Map.of("connectionId", "replacement")));
            when(sessions.getSession(2L)).thenReturn(replacement);
            outbox.resumeForUser(2);
            var restartedPublisher = new MessageOutboxPublisher(new JdbcMessageOutbox(jdbc, manager), delivery,
                    mock(SessionCacheInvalidationService.class), new DeliveryEnvelope(outbox, new ObjectMapper()), redis);
            restartedPublisher.publish();
            assertEquals("AWAITING_ACK", statusFor(2));
            verify(sessions).sendToUser(eq(2L), anyString());

            jdbc.update("UPDATE tb_message_outbox SET available_at = CURRENT_TIMESTAMP(3) WHERE target_user_id = 2");
            restartedPublisher.publish();
            verify(sessions, times(2)).sendToUser(eq(2L), anyString());
            var receipt = new ChatMsgDTO();
            receipt.setMessageId(sent.getMessageId());
            new com.zw.sserver.websocket.handler.DeliveryAckHandler(acknowledgements(chatMapper), router, instances)
                    .handle(null, 2L, receipt, "{}");
            restartedPublisher.publish();
            assertEquals("DELIVERED", statusFor(2));
            assertEquals(1, chatMapper.selectById(sent.getMessageId()).getIsRead());
            verify(sessions, times(2)).sendToUser(eq(2L), anyString());
        } finally {
            connections.destroy();
        }
    }

    private String statusFor(long recipient) {
        return jdbc.queryForObject("SELECT status FROM tb_message_outbox WHERE target_user_id = ?", String.class, recipient);
    }

    @Test
    void recallFencesQueuedContentAndOldReceiptsAndRetriesAnIdempotentTombstone() {
        var sent = useCase(outbox, sequence()).execute(command("recall", 2L));
        var old = outbox.claim(10).stream().filter(entry -> entry.targetUserId() == 2).findFirst().orElseThrow();
        var envelopes = new DeliveryEnvelope(outbox, new ObjectMapper());
        String oldPayload = envelopes.stamp(old);
        assertTrue(envelopes.maySend(2, oldPayload));
        var recall = recalls(outbox);
        recall.recall(1, sent.getMessageId());
        assertFalse(envelopes.maySend(2, oldPayload));
        assertFalse(outbox.complete(old));
        assertTrue(acknowledgements(chatMapper).acknowledge(sent.getMessageId(), 2, 0).isEmpty());
        assertEquals("PENDING", statusFor(2));
        var replacement = outbox.claim(10).stream().filter(entry -> entry.targetUserId() == 2).findFirst().orElseThrow();
        assertEquals(1, replacement.revision());
        assertTrue(replacement.payload().contains("\"recalled\":true"));
        assertFalse(replacement.payload().contains("hello"));
        assertTrue(envelopes.maySend(2, envelopes.stamp(replacement)));
        recall.recall(1, sent.getMessageId());
        assertEquals(1, jdbc.queryForObject("SELECT delivery_revision FROM tb_message_outbox WHERE target_user_id = 2", Integer.class));
        assertEquals(java.util.Optional.of(1L), acknowledgements(chatMapper).acknowledge(sent.getMessageId(), 2, 1));
        assertEquals("DELIVERED", statusFor(2));
    }

    @Test
    void recallCannotPartiallyCommitWhenOutboxReplacementFails() {
        var sent = useCase(outbox, sequence()).execute(command("recall-rollback", 2L));
        var broken = spy(outbox);
        doThrow(new IllegalStateException("outbox unavailable")).when(broken).replaceWithRecall(anyLong(), any(), anyString());
        assertThrows(IllegalStateException.class, () -> recalls(broken).recall(1, sent.getMessageId()));
        assertTrue(chatMapper.selectById(sent.getMessageId()).getCipherContent().contains("hello"));
        assertEquals(0, jdbc.queryForObject("SELECT delivery_revision FROM tb_message_outbox WHERE target_user_id = 2", Integer.class));
    }

    @Test
    void dismissalCancelsAllDeliveriesAndAStaleMemberSnapshotCannotSendAfterCommit() throws Exception {
        var cache = mock(com.zw.sserver.service.GroupMemberCacheService.class);
        var groups = transactional(new com.zw.sserver.service.GroupService(groupInfoMapper, groupMemberMapper,
                groupMessageMapper, cache, mock(com.zw.sserver.service.GroupReadCursorService.class), outbox));
        var group = groups.createGroup(1L, "lifecycle", List.of(2L, 3L));
        when(cache.isMember(group.getGroupId(), 1L)).thenReturn(true);
        when(cache.getMemberIds(group.getGroupId())).thenReturn(Set.of(1L, 2L, 3L));
        var membership = new GroupMemberCacheAdapter(cache, groupInfoMapper, groupMemberMapper);
        var send = new SendGroupMessageUseCase(new ObjectMapper(), new GroupMessagePolicy(), membership,
                sequence(), outbox, groupPersistence, manager, mock(MessageSendAdmission.class));
        var message = new GroupMsgDTO();
        message.setGroupId(group.getGroupId());
        message.setMsgType("GROUP_TEXT");
        message.setClientMsgId("before-dismiss");
        message.setContent("group body");
        assertTrue(send.execute(new SendGroupMessageCommand(1L, message)).isSuccess());
        var envelopes = new DeliveryEnvelope(outbox, new ObjectMapper());
        var old = outbox.claim(10).stream().filter(entry -> entry.targetUserId() == 2).findFirst().orElseThrow();
        String queued = envelopes.stamp(old);
        var deleted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Future<?> dismissal = workers.submit(() -> transactions.executeWithoutResult(status -> {
            groups.dismissGroup(group.getGroupId(), 1L);
            deleted.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            } catch (InterruptedException exception) {
                throw new IllegalStateException(exception);
            }
        }));
        assertTrue(deleted.await(5, TimeUnit.SECONDS));
        var late = new GroupMsgDTO();
        late.setGroupId(group.getGroupId());
        late.setMsgType("GROUP_TEXT");
        late.setClientMsgId("after-dismiss");
        late.setContent("must not resurrect group");
        Future<SendMessageResult> lateSend = workers.submit(() -> send.execute(new SendGroupMessageCommand(1L, late)));
        try {
            assertThrows(TimeoutException.class, () -> lateSend.get(200, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
        }
        dismissal.get(5, TimeUnit.SECONDS);
        assertFalse(lateSend.get(5, TimeUnit.SECONDS).isSuccess());
        assertEquals(0, count("tb_group_message"));
        assertFalse(envelopes.maySend(2, queued));
        assertFalse(outbox.complete(old));
        outbox.resumeForUser(2);
        assertTrue(outbox.claim(10).isEmpty());
        assertEquals("CANCELLED", statusFor(2));
    }

    private com.zw.sserver.application.message.MessageRecallService recalls(JdbcMessageOutbox events) {
        return transactional(new com.zw.sserver.application.message.MessageRecallService(chatMapper,
                groupMessageMapper, groupInfoMapper, groupMemberMapper, events, new ObjectMapper()));
    }

    @SuppressWarnings("unchecked")
    private <ServiceType> ServiceType transactional(ServiceType service) {
        var factory = new org.springframework.aop.framework.ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (ServiceType) factory.getProxy();
    }

    private com.zw.sserver.application.message.DeliveryAcknowledgementService acknowledgements(
            com.zw.sserver.mapper.ChatMessageMapper mapper) {
        var service = new com.zw.sserver.application.message.DeliveryAcknowledgementService(outbox, mapper);
        var factory = new org.springframework.aop.framework.ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (com.zw.sserver.application.message.DeliveryAcknowledgementService) factory.getProxy();
    }

    @Test
    void acknowledgementRollsBackIfPrivateDeliveryStateCannotBeUpdated() {
        var sent = useCase(outbox, sequence()).execute(command("ack-rollback", 2L));
        var broken = mock(com.zw.sserver.mapper.ChatMessageMapper.class);
        when(broken.selectById(sent.getMessageId())).thenThrow(new IllegalStateException("database failure"));
        assertThrows(IllegalStateException.class, () -> acknowledgements(broken).acknowledge(sent.getMessageId(), 2));
        assertEquals("PENDING", statusFor(2));
        assertEquals(0, chatMapper.selectById(sent.getMessageId()).getIsRead());
    }

    @Test
    void higherSequenceDeliveryReceiptDoesNotAcknowledgeMissingEarlierMessages() {
        var first = useCase(outbox, sequence()).execute(command("earlier", 2L));
        var second = useCase(outbox, sequence()).execute(command("later", 2L));
        var router = mock(com.zw.sserver.router.CrossNodeRouter.class);
        var instances = mock(com.zw.sserver.registry.InstanceRegistry.class);
        var receipt = new ChatMsgDTO();
        receipt.setToId(1L);
        receipt.setMessageId(second.getMessageId());
        new com.zw.sserver.websocket.handler.DeliveredHandler(chatMapper, router, instances)
                .handle(null, 2L, receipt, "{}");
        assertEquals(0, chatMapper.selectById(first.getMessageId()).getIsRead());
        assertEquals(1, chatMapper.selectById(second.getMessageId()).getIsRead());
        var forwarded = org.mockito.ArgumentCaptor.forClass(com.zw.sserver.model.dto.RouteMessage.class);
        verify(router).route(forwarded.capture());
        assertTrue(forwarded.getValue().getPayload().contains("\"" + second.getMessageId() + "\""));
        receipt.setMessageId(first.getMessageId());
        new com.zw.sserver.websocket.handler.DeliveredHandler(chatMapper, router, instances)
                .handle(null, 999L, receipt, "{}");
        assertEquals(0, chatMapper.selectById(first.getMessageId()).getIsRead());
    }

    private ConversationSequencePort sequence() {
        return new JdbcConversationSequenceAdapter(new ConversationSeqService(jdbc));
    }

    private ConversationSequencePort delayedSequence(CountDownLatch allocated, CountDownLatch release) {
        return new ConversationSequencePort() {
            public long nextPrivateSeq(Long senderId, Long receiverId) {
                long next = sequence().nextPrivateSeq(senderId, receiverId);
                allocated.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return next;
            }

            public long nextGroupSeq(Long groupId) {
                return sequence().nextGroupSeq(groupId);
            }
        };
    }

    private SendPrivateMessageUseCase useCase(MessageOutboxPort events, ConversationSequencePort sequence) {
        return new SendPrivateMessageUseCase(new ObjectMapper(), new PrivateMessagePolicy(), sequence,
                events, privatePersistence, manager, mock(MessageSendAdmission.class));
    }

    private SendPrivateMessageCommand command(String clientMsgId, long receiverId) {
        var message = new ChatMsgDTO();
        message.setToId(receiverId);
        message.setClientMsgId(clientMsgId);
        message.setContent("hello");
        message.setMsgType("TEXT");
        return new SendPrivateMessageCommand(1L, message);
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.data.redis.core.StringRedisTemplate healthyRedis() {
        var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        when(redis.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn("PONG");
        return redis;
    }
}
