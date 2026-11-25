package com.zw.sserver.application.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.scommon.ratelimit.RedisTokenBucket;
import com.zw.sserver.domain.message.repository.GroupMessagePersistencePort;
import com.zw.sserver.domain.message.repository.MessageOutboxPort;
import com.zw.sserver.domain.message.repository.PrivateMessagePersistencePort;
import com.zw.sserver.domain.message.service.ConversationSequencePort;
import com.zw.sserver.domain.message.service.GroupMembershipPort;
import com.zw.sserver.domain.message.service.GroupMessagePolicy;
import com.zw.sserver.domain.message.service.PrivateMessagePolicy;
import com.zw.sserver.model.dto.ChatMsgDTO;
import com.zw.sserver.websocket.handler.ChatMessageHandler;
import com.zw.sserver.websocket.handler.GroupMessageHandler;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SCHAT_TEST_REDIS_PORT", matches = ".+")
class MessageSendAdmissionIntegrationTest {

    private static LettuceConnectionFactory connections;
    private StringRedisTemplate redis;
    private static final long USER_ID = 900000001L;
    private final String messageKey = "schat:rate:message:" + USER_ID;

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("SCHAT_TEST_REDIS_PORT")));
        connections.afterPropertiesSet();
        connections.start();
    }

    @BeforeEach
    void reset() {
        redis = new StringRedisTemplate(connections);
        redis.delete(messageKey);
    }

    @AfterEach
    void cleanup() {
        redis.delete(messageKey);
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    private void exhaustBudget() {
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>("""
                local clock = redis.call('TIME')
                local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
                redis.call('HSET', KEYS[1], 'tokens', 0, 'updated', now + 60000)
                redis.call('PEXPIRE', KEYS[1], 120000)
                return 1
                """, Long.class);
        redis.execute(script, List.of(messageKey));
    }

    @Test
    void httpPrivateHttpGroupAndWebsocketAllUseTheSameSharedAdmission() throws Exception {
        var mapper = new ObjectMapper();
        var sequence = mock(ConversationSequencePort.class);
        when(sequence.nextPrivateSeq(anyLong(), anyLong())).thenReturn(1L);
        when(sequence.nextGroupSeq(anyLong())).thenReturn(1L);
        var privateMessages = mock(PrivateMessagePersistencePort.class);
        var groupMessages = mock(GroupMessagePersistencePort.class);
        var outbox = mock(MessageOutboxPort.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        var membership = mock(GroupMembershipPort.class);
        when(membership.isMember(10L, USER_ID)).thenReturn(true);
        when(membership.memberIds(10L)).thenReturn(Set.of(USER_ID, 2L));
        when(membership.lockAndLoadMembers(10L, USER_ID)).thenReturn(Set.of(USER_ID, 2L));
        var privateUseCase = new SendPrivateMessageUseCase(mapper, new PrivateMessagePolicy(), sequence,
                outbox, privateMessages, manager, new MessageSendAdmission(redis));
        var groupUseCase = new SendGroupMessageUseCase(mapper, new GroupMessagePolicy(), membership, sequence,
                outbox, groupMessages, manager, new MessageSendAdmission(redis));
        var http = new ChatHttpApplicationService(null, null, null, mapper, null, null, null,
                null, null, privateUseCase, groupUseCase, null);
        var body = Map.<String, Object>of("clientMsgId", "http", "type", "text", "content", "hello");
        assertEquals(200, http.sendMessage(USER_ID, "2", body).getCode());
        assertEquals(200, http.sendMessage(USER_ID, "group_10", body).getCode());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(messageKey)));
        clearInvocations(sequence, privateMessages, groupMessages, outbox, manager, membership);
        var oversized = Map.<String, Object>of("clientMsgId", "oversized", "content", "x".repeat(5001));
        assertNotEquals(200, http.sendMessage(USER_ID, "2", oversized).getCode());
        assertNotEquals(200, http.sendMessage(USER_ID, "group_10", oversized).getCode());
        verifyNoInteractions(sequence, privateMessages, groupMessages, outbox, manager, membership);
        exhaustBudget();

        assertNotEquals(200, http.sendMessage(USER_ID, "2", body).getCode());
        assertNotEquals(200, http.sendMessage(USER_ID, "group_10", body).getCode());
        var session = mock(Session.class);
        when(session.getUserProperties()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
        var remote = mock(RemoteEndpoint.Basic.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(remote);
        var dto = new ChatMsgDTO();
        dto.setClientMsgId("ws");
        dto.setMsgType("TEXT");
        dto.setToId(2L);
        dto.setContent("hello");
        new ChatMessageHandler(mapper, privateUseCase).handle(session, USER_ID, dto, "{}");
        dto.setGroupId(10L);
        dto.setMsgType("GROUP_TEXT");
        new GroupMessageHandler(mapper, groupUseCase).handle(session, USER_ID, dto, "{}");
        verify(remote, times(2)).sendText(contains("发送过于频繁"));
        verifyNoInteractions(sequence, privateMessages, groupMessages, outbox, manager, membership);
    }

    @Test
    void separateLimiterInstancesCannotSpendTheSameLastTokenConcurrently() throws Exception {
        String key = "schat:rate:test:" + UUID.randomUUID();
        var first = new RedisTokenBucket(redis);
        var second = new RedisTokenBucket(new StringRedisTemplate(connections));
        var workers = Executors.newFixedThreadPool(8);
        try {
            var tasks = java.util.stream.IntStream.range(0, 32)
                    .<java.util.concurrent.Callable<Boolean>>mapToObj(index -> () ->
                            (index % 2 == 0 ? first : second).tryAcquire(key, 1, 0.001)).toList();
            long allowed = 0;
            for (var result : workers.invokeAll(tasks)) {
                if (result.get()) allowed++;
            }
            assertEquals(1, allowed);
            assertTrue(redis.getExpire(key) > 0);
        } finally {
            workers.shutdownNow();
            redis.delete(key);
        }
    }

    @Test
    void bucketRefillsAndExpiresWithoutAnyLocalCleanupJob() throws Exception {
        String key = "schat:rate:test:" + UUID.randomUUID();
        var bucket = new RedisTokenBucket(redis);
        try {
            assertTrue(bucket.tryAcquire(key, 1, 1.0));
            assertFalse(bucket.tryAcquire(key, 1, 1.0));
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            boolean refilled = false;
            while (!refilled && System.nanoTime() < deadline) {
                Thread.sleep(25);
                refilled = bucket.tryAcquire(key, 1, 1.0);
            }
            assertTrue(refilled);
            assertTrue(redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS) <= 2000);
            deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) Thread.sleep(25);
            assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
        } finally {
            redis.delete(key);
        }
    }
}
