package com.zw.sserver.mq.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.application.session.SessionCacheInvalidationService;
import com.zw.sserver.config.SchatProperties;
import com.zw.sserver.mapper.ChatMessageMapper;
import com.zw.sserver.model.entity.ChatMessage;
import com.zw.sserver.mq.RedisInboxService;
import com.zw.sserver.registry.InstanceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * MessageQueue 单元测试
 * <p>
 * 测试投递失败回退同步写库、重试指数退避、死信强制 XACK 流程（补丁 2）
 */
class MessageQueueUnitTest {

    // ========== Fixture Helpers (same pattern as MessageQueuePropertyTest) ==========

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
    private ConsumerFixture createConsumerFixture(int maxRetries) {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        InstanceRegistry instanceRegistry = mock(InstanceRegistry.class);
        RedisInboxService redisInboxService = mock(RedisInboxService.class);
        SessionCacheInvalidationService sessionCacheInvalidationService =
                mock(SessionCacheInvalidationService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        lenient().when(stringRedisTemplate.opsForStream()).thenReturn(streamOps);
        lenient().when(instanceRegistry.getInstanceId()).thenReturn("test-node:8082");

        SchatProperties props = new SchatProperties();
        props.getInstance().setId("test-node:8082");
        props.getRedis().getStream().setMaxRetries(maxRetries);

        RedisMessageQueueConsumer consumer = new RedisMessageQueueConsumer(
                stringRedisTemplate, chatMessageMapper, instanceRegistry, props, objectMapper,
                sessionCacheInvalidationService);

        // Inject optional redisInboxService via reflection
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

    private ConsumerFixture createConsumerFixture() {
        return createConsumerFixture(3);
    }

    private ChatMessage sampleMessage() {
        ChatMessage msg = new ChatMessage();
        msg.setMessageId(100001L);
        msg.setClientMsgId("client-abc-123");
        msg.setSenderId(1L);
        msg.setReceiverId(2L);
        msg.setCipherContent("encrypted-hello");
        msg.setSendTime(new Date(1700000000000L));
        msg.setIsRead(0);
        return msg;
    }

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

    // ========== Test 1: Producer returns false on XADD failure ==========

    @Test
    @DisplayName("Producer: produce() returns false when XADD throws exception")
    void produceReturnsFalseOnXaddFailure() {
        ProducerFixture pf = createProducerFixture();

        when(pf.streamOps.add(any(StringRecord.class)))
                .thenThrow(new RuntimeException("Redis connection refused"));

        boolean result = pf.producer.produce(sampleMessage());

        assertFalse(result, "produce() should return false when XADD fails");
    }

    // ========== Test 2: Producer returns true on success ==========

    @Test
    @DisplayName("Producer: produce() returns true on success")
    void produceReturnsTrueOnSuccess() {
        ProducerFixture pf = createProducerFixture();

        RecordId mockRecordId = RecordId.of("1700000000000-0");
        when(pf.streamOps.add(any(StringRecord.class))).thenReturn(mockRecordId);

        boolean result = pf.producer.produce(sampleMessage());

        assertTrue(result, "produce() should return true when XADD succeeds");
        verify(pf.streamOps).add(any(StringRecord.class));
    }

    // ========== Test 3: Consumer successful persistence flow ==========

    @Test
    @DisplayName("Consumer: successful persistence — INSERT → ZREM Inbox → XACK")
    void consumerSuccessfulPersistenceFlow() {
        ConsumerFixture cf = createConsumerFixture();
        ChatMessage msg = sampleMessage();
        MapRecord<String, Object, Object> record = buildRecord(msg, "1700000000000-0");

        when(cf.chatMessageMapper.insert(any(ChatMessage.class))).thenReturn(1);

        cf.consumer.processRecordWithRetry(record, "persist-consumer-test");

        // 1. INSERT was called
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(cf.chatMessageMapper).insert(captor.capture());
        assertEquals(msg.getMessageId(), captor.getValue().getMessageId());

        // 2. Session cache was evicted
        verify(cf.sessionCacheInvalidationService)
                .evictPrivateConversation(msg.getSenderId(), msg.getReceiverId());

        // 3. Inbox ZREM was called
        verify(cf.redisInboxService).removeFromInbox(msg.getReceiverId(), msg.getMessageId());

        // 4. XACK was called
        verify(cf.streamOps).acknowledge(
                eq(RedisMessageQueueConsumer.STREAM_KEY),
                eq(RedisMessageQueueConsumer.GROUP_NAME),
                eq(record.getId()));
    }

    // ========== Test 4: Consumer retry with exponential backoff ==========

    @Test
    @DisplayName("Consumer: retry with exponential backoff — fails first 2 times, succeeds on 3rd")
    void consumerRetryWithExponentialBackoff() {
        // Use maxRetries=3 but the test will succeed on 3rd attempt
        ConsumerFixture cf = createConsumerFixture(3);
        ChatMessage msg = sampleMessage();
        MapRecord<String, Object, Object> record = buildRecord(msg, "1700000000000-0");

        // Fail first 2 times, succeed on 3rd
        when(cf.chatMessageMapper.insert(any(ChatMessage.class)))
                .thenThrow(new RuntimeException("DB timeout"))
                .thenThrow(new RuntimeException("DB timeout"))
                .thenReturn(1);

        long startTime = System.currentTimeMillis();
        cf.consumer.processRecordWithRetry(record, "persist-consumer-test");
        long elapsed = System.currentTimeMillis() - startTime;

        // mapper.insert() was called 3 times (2 failures + 1 success)
        verify(cf.chatMessageMapper, times(3)).insert(any(ChatMessage.class));

        // XACK was called (success path)
        verify(cf.streamOps).acknowledge(
                eq(RedisMessageQueueConsumer.STREAM_KEY),
                eq(RedisMessageQueueConsumer.GROUP_NAME),
                eq(record.getId()));

        // No dead letter transfer
        verify(cf.streamOps, never()).add(any(StringRecord.class));

        // Verify exponential backoff happened: at least 1s + 2s = 3s of sleep
        assertTrue(elapsed >= 2500, "Should have waited at least ~3s for exponential backoff (1s + 2s), actual: " + elapsed + "ms");
    }

    // ========== Test 5: Consumer dead letter forced XACK after max retries (补丁 2) ==========

    @Test
    @DisplayName("Consumer: dead letter forced XACK after max retries (补丁 2)")
    void consumerDeadLetterForcedXack() {
        // Use maxRetries=1 to keep test fast (no Thread.sleep delays)
        ConsumerFixture cf = createConsumerFixture(1);
        ChatMessage msg = sampleMessage();
        MapRecord<String, Object, Object> record = buildRecord(msg, "1700000000000-0");

        // mapper.insert() ALWAYS fails
        when(cf.chatMessageMapper.insert(any(ChatMessage.class)))
                .thenThrow(new RuntimeException("Persistent DB failure"));

        // Dead letter XADD succeeds
        when(cf.streamOps.add(any(StringRecord.class))).thenReturn(RecordId.of("1700000000001-0"));

        cf.consumer.processRecordWithRetry(record, "persist-consumer-test");

        // 1. mapper.insert() was called exactly maxRetries times
        verify(cf.chatMessageMapper, times(1)).insert(any(ChatMessage.class));

        // 2. Dead letter transfer happened
        ArgumentCaptor<StringRecord> dlCaptor = ArgumentCaptor.forClass(StringRecord.class);
        verify(cf.streamOps).add(dlCaptor.capture());
        StringRecord dlRecord = dlCaptor.getValue();

        assertEquals(RedisMessageQueueConsumer.DEAD_LETTER_KEY, dlRecord.getStream(),
                "Dead letter should be written to schat:mq:deadletter");
        Map<String, String> dlFields = dlRecord.getValue();
        assertEquals(String.valueOf(msg.getMessageId()), dlFields.get("messageId"));
        assertEquals(RedisMessageQueueConsumer.STREAM_KEY, dlFields.get("originalStreamKey"));
        assertEquals("1", dlFields.get("retryCount"));
        assertNotNull(dlFields.get("failReason"));

        // 3. Force XACK on main stream — critical 补丁 2 requirement
        verify(cf.streamOps).acknowledge(
                eq(RedisMessageQueueConsumer.STREAM_KEY),
                eq(RedisMessageQueueConsumer.GROUP_NAME),
                eq(record.getId()));

        // 4. Inbox ZREM was NOT called (insert never succeeded)
        verify(cf.redisInboxService, never()).removeFromInbox(anyLong(), anyLong());
    }

    // ========== Test 6: Consumer deserialization failure discards message ==========

    @Test
    @DisplayName("Consumer: deserialization failure discards message with XACK, no insert")
    void consumerDeserializationFailureDiscardsMessage() {
        ConsumerFixture cf = createConsumerFixture();

        // Build a malformed record with non-numeric messageId
        Map<Object, Object> malformedFields = new LinkedHashMap<>();
        malformedFields.put("messageId", "not-a-number");
        malformedFields.put("senderId", "also-bad");
        malformedFields.put("receiverId", "garbage");

        MapRecord<String, Object, Object> record = MapRecord.create(
                        RedisMessageQueueConsumer.STREAM_KEY, malformedFields)
                .withId(RecordId.of("1700000000000-0"));

        cf.consumer.processRecordWithRetry(record, "persist-consumer-test");

        // XACK was called (discard the malformed message)
        verify(cf.streamOps).acknowledge(
                eq(RedisMessageQueueConsumer.STREAM_KEY),
                eq(RedisMessageQueueConsumer.GROUP_NAME),
                eq(record.getId()));

        // mapper.insert() was NEVER called
        verify(cf.chatMessageMapper, never()).insert(any(ChatMessage.class));

        // Inbox was NEVER touched
        verify(cf.redisInboxService, never()).removeFromInbox(anyLong(), anyLong());
    }

    // ========== Test 7: Consumer Inbox cleanup failure doesn't block persistence ==========

    @Test
    @DisplayName("Consumer: Inbox cleanup failure doesn't block persistence — XACK still happens")
    void consumerInboxCleanupFailureDoesNotBlockPersistence() {
        ConsumerFixture cf = createConsumerFixture();
        ChatMessage msg = sampleMessage();
        MapRecord<String, Object, Object> record = buildRecord(msg, "1700000000000-0");

        // mapper.insert() succeeds
        when(cf.chatMessageMapper.insert(any(ChatMessage.class))).thenReturn(1);

        // Inbox removeFromInbox() throws
        doThrow(new RuntimeException("Redis Inbox connection lost"))
                .when(cf.redisInboxService).removeFromInbox(anyLong(), anyLong());

        cf.consumer.processRecordWithRetry(record, "persist-consumer-test");

        // 1. INSERT was called and succeeded
        verify(cf.chatMessageMapper).insert(any(ChatMessage.class));

        // 2. Inbox removal was attempted
        verify(cf.redisInboxService).removeFromInbox(msg.getReceiverId(), msg.getMessageId());

        // 3. XACK still happened despite Inbox failure
        verify(cf.streamOps).acknowledge(
                eq(RedisMessageQueueConsumer.STREAM_KEY),
                eq(RedisMessageQueueConsumer.GROUP_NAME),
                eq(record.getId()));
    }
}
