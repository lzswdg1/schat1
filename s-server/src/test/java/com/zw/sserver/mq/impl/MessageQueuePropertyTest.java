package com.zw.sserver.mq.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.application.session.SessionCacheInvalidationService;
import com.zw.sserver.config.SchatProperties;
import com.zw.sserver.mapper.ChatMessageMapper;
import com.zw.sserver.model.entity.ChatMessage;
import com.zw.sserver.mq.RedisInboxService;
import com.zw.sserver.registry.InstanceRegistry;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MessageQueue 属性测试
 * 使用 jqwik 框架，每个属性测试至少运行 100 次迭代
 */
class MessageQueuePropertyTest {

    // ========== Generators ==========

    @Provide
    Arbitrary<ChatMessage> chatMessages() {
        return Combinators.combine(
                Arbitraries.longs().between(1L, Long.MAX_VALUE),           // messageId
                Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(32), // clientMsgId
                Arbitraries.longs().between(1L, 1_000_000L),              // senderId
                Arbitraries.longs().between(1L, 1_000_000L),              // receiverId
                Arbitraries.strings().ofMinLength(1).ofMaxLength(200),    // cipherContent
                Arbitraries.longs().between(1_000_000_000_000L, 2_000_000_000_000L) // sendTime millis
        ).as((msgId, clientMsgId, senderId, receiverId, cipher, sendTimeMs) -> {
            ChatMessage msg = new ChatMessage();
            msg.setMessageId(msgId);
            msg.setClientMsgId(clientMsgId);
            msg.setSenderId(senderId);
            msg.setReceiverId(receiverId);
            msg.setCipherContent(cipher);
            msg.setSendTime(new Date(sendTimeMs));
            msg.setIsRead(0);
            return msg;
        });
    }

    @Provide
    Arbitrary<String> instanceIds() {
        Arbitrary<String> hostnamePort = Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10),
                Arbitraries.integers().between(1024, 65535)
        ).as((host, port) -> host + ":" + port);

        Arbitrary<String> ipPort = Combinators.combine(
                Arbitraries.integers().between(1, 254),
                Arbitraries.integers().between(0, 254),
                Arbitraries.integers().between(0, 254),
                Arbitraries.integers().between(1, 254),
                Arbitraries.integers().between(1024, 65535)
        ).as((a, b, c, d, port) -> a + "." + b + "." + c + "." + d + ":" + port);

        return Arbitraries.oneOf(hostnamePort, ipPort);
    }

    // ========== Helper ==========

    @SuppressWarnings("unchecked")
    private record ProducerFixture(
            RedisMessageQueueProducer producer,
            StringRedisTemplate stringRedisTemplate,
            StreamOperations<String, Object, Object> streamOps,
            SchatProperties schatProperties
    ) {}

    @SuppressWarnings("unchecked")
    private ProducerFixture createProducerFixture() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        lenient().when(stringRedisTemplate.opsForStream()).thenReturn(streamOps);

        SchatProperties props = new SchatProperties();
        props.getInstance().setId("test-node:8082");
        props.getRedis().getStream().setMaxLen(1000);

        RedisMessageQueueProducer producer = new RedisMessageQueueProducer(stringRedisTemplate, props);
        return new ProducerFixture(producer, stringRedisTemplate, streamOps, props);
    }

    @SuppressWarnings("unchecked")
    private record ConsumerFixture(
            RedisMessageQueueConsumer consumer,
            StringRedisTemplate stringRedisTemplate,
            StreamOperations<String, Object, Object> streamOps,
            ChatMessageMapper chatMessageMapper,
            RedisInboxService redisInboxService,
            SessionCacheInvalidationService sessionCacheInvalidationService,
            InstanceRegistry instanceRegistry,
            SchatProperties schatProperties
    ) {}

    @SuppressWarnings("unchecked")
    private ConsumerFixture createConsumerFixture(String instanceId, int maxRetries) {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        InstanceRegistry instanceRegistry = mock(InstanceRegistry.class);
        RedisInboxService redisInboxService = mock(RedisInboxService.class);
        SessionCacheInvalidationService sessionCacheInvalidationService =
                mock(SessionCacheInvalidationService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        lenient().when(stringRedisTemplate.opsForStream()).thenReturn(streamOps);
        lenient().when(instanceRegistry.getInstanceId()).thenReturn(instanceId);

        SchatProperties props = new SchatProperties();
        props.getInstance().setId(instanceId);
        props.getRedis().getStream().setMaxRetries(maxRetries);

        RedisMessageQueueConsumer consumer = new RedisMessageQueueConsumer(
                stringRedisTemplate, chatMessageMapper, instanceRegistry, props, objectMapper,
                sessionCacheInvalidationService);

        // Inject the optional redisInboxService via reflection
        try {
            var field = RedisMessageQueueConsumer.class.getDeclaredField("redisInboxService");
            field.setAccessible(true);
            field.set(consumer, redisInboxService);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject redisInboxService", e);
        }

        return new ConsumerFixture(consumer, stringRedisTemplate, streamOps,
                chatMessageMapper, redisInboxService, sessionCacheInvalidationService,
                instanceRegistry, props);
    }

    @SuppressWarnings("unchecked")
    private ConsumerFixture createConsumerFixture(String instanceId) {
        return createConsumerFixture(instanceId, 3);
    }

    /**
     * Build a MapRecord from a ChatMessage, simulating what the producer writes to the stream.
     */
    private MapRecord<String, Object, Object> buildRecord(ChatMessage msg, String recordIdStr) {
        Map<Object, Object> fields = new LinkedHashMap<>();
        fields.put("messageId", String.valueOf(msg.getMessageId()));
        fields.put("clientMsgId", msg.getClientMsgId() != null ? msg.getClientMsgId() : "");
        fields.put("senderId", String.valueOf(msg.getSenderId()));
        fields.put("receiverId", String.valueOf(msg.getReceiverId()));
        fields.put("cipherContent", msg.getCipherContent() != null ? msg.getCipherContent() : "");
        fields.put("sendTime", String.valueOf(msg.getSendTime() != null ? msg.getSendTime().getTime() : 0));
        if (msg.getQuoteMsgId() != null) {
            fields.put("quoteMsgId", String.valueOf(msg.getQuoteMsgId()));
        }
        if (msg.getQuoteContent() != null) {
            fields.put("quoteContent", msg.getQuoteContent());
        }

        return MapRecord.create(RedisMessageQueueConsumer.STREAM_KEY, fields)
                .withId(RecordId.of(recordIdStr));
    }

    // ========== Property Tests ==========

    // Feature: redis-message-routing, Property 12: 消息持久化队列往返一致性
    // **Validates: Requirements 5.2**
    @Property(tries = 100)
    void produceConsumeRoundTrip(
            @ForAll("chatMessages") ChatMessage originalMsg,
            @ForAll("instanceIds") String instanceId) {

        // Setup consumer fixture
        ConsumerFixture cf = createConsumerFixture(instanceId);
        String consumerName = "persist-consumer-" + instanceId;

        // Simulate a record as produced by RedisMessageQueueProducer
        MapRecord<String, Object, Object> record = buildRecord(originalMsg, "1700000000000-0");

        // Mock: mapper.insert() succeeds
        when(cf.chatMessageMapper.insert(any(ChatMessage.class))).thenReturn(1);

        // Act: consumer processes the record
        cf.consumer.processRecordWithRetry(record, consumerName);

        // Assert 1: mapper.insert() was called with an equivalent ChatMessage
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(cf.chatMessageMapper).insert(captor.capture());
        ChatMessage persisted = captor.getValue();

        assertEquals(originalMsg.getMessageId(), persisted.getMessageId(),
                "Persisted messageId should match original");
        assertEquals(originalMsg.getSenderId(), persisted.getSenderId(),
                "Persisted senderId should match original");
        assertEquals(originalMsg.getReceiverId(), persisted.getReceiverId(),
                "Persisted receiverId should match original");
        assertEquals(originalMsg.getCipherContent(), persisted.getCipherContent(),
                "Persisted cipherContent should match original");

        // sendTime comparison: compare millis (Date precision)
        if (originalMsg.getSendTime() != null) {
            assertNotNull(persisted.getSendTime(), "Persisted sendTime should not be null");
            assertEquals(originalMsg.getSendTime().getTime(), persisted.getSendTime().getTime(),
                    "Persisted sendTime should match original");
        }

        // Assert 2: related session cache is invalidated after successful persistence
        verify(cf.sessionCacheInvalidationService)
                .evictPrivateConversation(originalMsg.getSenderId(), originalMsg.getReceiverId());

        // Assert 3: XACK was called after successful persistence
        verify(cf.streamOps).acknowledge(
                eq(RedisMessageQueueConsumer.STREAM_KEY),
                eq(RedisMessageQueueConsumer.GROUP_NAME),
                eq(record.getId()));
    }

    // Feature: redis-message-routing, Property 13: 队列投递成功后返回 ACK
    // **Validates: Requirements 5.3**
    @Property(tries = 100)
    void successfulQueueReturnsAck(@ForAll("chatMessages") ChatMessage msg) {

        ProducerFixture pf = createProducerFixture();

        // Mock: XADD succeeds and returns a valid RecordId
        RecordId mockRecordId = RecordId.of("1700000000000-0");
        when(pf.streamOps.add(any(StringRecord.class))).thenReturn(mockRecordId);

        // Act
        boolean result = pf.producer.produce(msg);

        // Assert: produce returns true when XADD succeeds
        assertTrue(result, "produce() should return true when XADD succeeds");

        // Assert: XADD was actually called
        verify(pf.streamOps).add(any(StringRecord.class));
    }

    // Feature: redis-message-routing, Property 21: 死信消息强制 XACK（补丁 2）
    // **Validates: Requirements 5.5, 补丁 2**
    @Property(tries = 100)
    void deadLetterForcesXack(
            @ForAll("chatMessages") ChatMessage originalMsg,
            @ForAll("instanceIds") String instanceId) {

        // Use maxRetries=1 to avoid Thread.sleep delays in retry loop (1s+2s per iteration).
        // The property validates the core invariant: after exhausting ALL retries,
        // the message MUST be transferred to dead letter queue AND force XACK'd.
        // The retry count is parameterized, so maxRetries=1 still exercises the full path.
        ConsumerFixture cf = createConsumerFixture(instanceId, 1);
        String consumerName = "persist-consumer-" + instanceId;

        // Simulate a record
        MapRecord<String, Object, Object> record = buildRecord(originalMsg, "1700000000000-0");

        // Mock: mapper.insert() ALWAYS throws — simulates persistent DB failure
        when(cf.chatMessageMapper.insert(any(ChatMessage.class)))
                .thenThrow(new RuntimeException("DB connection refused"));

        // Mock: dead letter XADD succeeds
        when(cf.streamOps.add(any(StringRecord.class))).thenReturn(RecordId.of("1700000000001-0"));

        // Act: consumer processes the record (will exhaust all retries)
        cf.consumer.processRecordWithRetry(record, consumerName);

        // Assert 1: mapper.insert() was called exactly maxRetries times
        verify(cf.chatMessageMapper, times(1)).insert(any(ChatMessage.class));

        // Assert 2: message was transferred to dead letter queue
        ArgumentCaptor<StringRecord> deadLetterCaptor = ArgumentCaptor.forClass(StringRecord.class);
        verify(cf.streamOps).add(deadLetterCaptor.capture());
        StringRecord deadLetterRecord = deadLetterCaptor.getValue();

        assertEquals(RedisMessageQueueConsumer.DEAD_LETTER_KEY, deadLetterRecord.getStream(),
                "Dead letter record should be written to the dead letter stream");

        Map<String, String> dlFields = deadLetterRecord.getValue();
        assertEquals(String.valueOf(originalMsg.getMessageId()), dlFields.get("messageId"),
                "Dead letter should contain the original messageId");
        assertEquals(RedisMessageQueueConsumer.STREAM_KEY, dlFields.get("originalStreamKey"),
                "Dead letter should reference the original stream key");
        assertEquals("1", dlFields.get("retryCount"),
                "Dead letter should record the retry count");

        // Assert 3: XACK was FORCED on the main stream — the critical patch 2 requirement
        // This is the most important assertion: after max retries, the message MUST be
        // removed from PEL to prevent infinite re-consumption (补丁 2)
        verify(cf.streamOps).acknowledge(
                eq(RedisMessageQueueConsumer.STREAM_KEY),
                eq(RedisMessageQueueConsumer.GROUP_NAME),
                eq(record.getId()));
    }
}
