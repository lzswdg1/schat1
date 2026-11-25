package com.zw.sserver.router.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.config.SchatProperties;
import com.zw.sserver.model.dto.RouteMessage;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.registry.OnlineUserRegistry;
import jakarta.websocket.Session;
import com.zw.sserver.websocket.LocalSessionManager;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MessageStream 属性测试
 * 使用 jqwik 框架，每个属性测试至少运行 100 次迭代
 */
class MessageStreamPropertyTest {

    // ========== Generators ==========

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

        Arbitrary<String> k8sPodName = Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10),
                Arbitraries.integers().between(0, 99)
        ).as((name, idx) -> name.toLowerCase() + "-" + idx);

        return Arbitraries.oneOf(hostnamePort, ipPort, k8sPodName);
    }

    @Provide
    Arbitrary<Long> userIds() {
        return Arbitraries.longs().between(1L, 1_000_000L);
    }

    @Provide
    Arbitrary<String> messageTypes() {
        return Arbitraries.of("CHAT", "READ", "NOTICE", "KICK_SESSION");
    }

    @Provide
    Arbitrary<String> payloads() {
        return Arbitraries.strings().ofMinLength(1).ofMaxLength(200);
    }

    @Provide
    Arbitrary<Long> maxLens() {
        return Arbitraries.longs().between(100L, 10000L);
    }

    // ========== Helper ==========

    @SuppressWarnings("unchecked")
    private record TestFixture(
            RedisMessageStreamConsumer consumer,
            StringRedisTemplate stringRedisTemplate,
            StreamOperations<String, Object, Object> streamOps,
            LocalSessionManager localSessionManager,
            InstanceRegistry instanceRegistry,
            SchatProperties schatProperties
    ) {}

    @SuppressWarnings("unchecked")
    private TestFixture createFixture(String instanceId, long maxLen) {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        LocalSessionManager localSessionManager = mock(LocalSessionManager.class);
        InstanceRegistry instanceRegistry = mock(InstanceRegistry.class);
        ObjectMapper objectMapper = new ObjectMapper();

        lenient().when(stringRedisTemplate.opsForStream()).thenReturn(streamOps);
        lenient().when(instanceRegistry.getInstanceId()).thenReturn(instanceId);

        SchatProperties props = new SchatProperties();
        props.getInstance().setId(instanceId);
        props.getRedis().getStream().setMaxLen(maxLen);

        RedisMessageStreamConsumer consumer = new RedisMessageStreamConsumer(
                stringRedisTemplate, localSessionManager, instanceRegistry, props, objectMapper, mock(OnlineUserRegistry.class));

        return new TestFixture(consumer, stringRedisTemplate, streamOps,
                localSessionManager, instanceRegistry, props);
    }

    @SuppressWarnings("unchecked")
    private TestFixture createFixture(String instanceId) {
        return createFixture(instanceId, 1000L);
    }

    // ========== Property Tests ==========

    // Feature: redis-message-routing, Property 9: unackedMessagesSurviveRestart
    // **Validates: Requirements 3.6**
    @Property(tries = 100)
    @SuppressWarnings("unchecked")
    void unackedMessagesSurviveRestart(
            @ForAll("instanceIds") String instanceId,
            @ForAll("userIds") Long targetUserId,
            @ForAll("messageTypes") String type,
            @ForAll("payloads") String payload) {

        // This test verifies the consumer group mechanism:
        // 1. Messages written to a stream but not XACK'd remain in the PEL
        // 2. After consumer restart, XREADGROUP from "0" re-reads pending messages
        // 3. The consumer processes and ACKs them

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        LocalSessionManager localSessionManager = mock(LocalSessionManager.class);
        InstanceRegistry instanceRegistry = mock(InstanceRegistry.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(instanceRegistry.getInstanceId()).thenReturn(instanceId);

        SchatProperties props = new SchatProperties();
        props.getInstance().setId(instanceId);

        String streamKey = "schat:route:" + instanceId;
        String groupName = "group-" + instanceId;
        String consumerName = "consumer-" + instanceId;

        // Simulate a pending message (unacked from previous run)
        Map<Object, Object> fields = new LinkedHashMap<>();
        fields.put("type", type);
        fields.put("targetUserId", targetUserId.toString());
        fields.put("payload", payload);
        fields.put("sourceInstanceId", "source-node");

        RecordId recordId = RecordId.of("1700000000000-0");
        MapRecord<String, Object, Object> pendingRecord = MapRecord.create(streamKey, fields).withId(recordId);

        // First XREADGROUP from "0" returns the pending message, second returns empty (no more pending)
        when(streamOps.read(
                eq(Consumer.from(groupName, consumerName)),
                any(StreamReadOptions.class),
                eq(StreamOffset.create(streamKey, ReadOffset.from("0")))
        )).thenReturn(List.of(pendingRecord)).thenReturn(Collections.emptyList());

        // XREADGROUP from ">" for new messages — return null to let the loop exit
        // (we'll stop the consumer after pending processing)
        when(streamOps.read(
                eq(Consumer.from(groupName, consumerName)),
                any(StreamReadOptions.class),
                eq(StreamOffset.create(streamKey, ReadOffset.lastConsumed()))
        )).thenReturn(null);

        when(localSessionManager.sendToUser(eq(targetUserId), anyString())).thenReturn(true);
        OnlineUserRegistry online = mock(OnlineUserRegistry.class);
        Session session = mock(Session.class);
        when(session.getUserProperties()).thenReturn(new HashMap<>(Map.of("connectionId", "current")));
        when(localSessionManager.getSession(targetUserId)).thenReturn(session);
        when(online.isCurrentConnection(targetUserId, instanceId, "current")).thenReturn(true);

        RedisMessageStreamConsumer consumer = new RedisMessageStreamConsumer(
                redisTemplate, localSessionManager, instanceRegistry, props, objectMapper, online);

        // Start consumer (it will process pending, then block on new messages)
        consumer.start();

        // Give the consumer thread time to process pending messages
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        consumer.stop();

        // Verify: the pending message was delivered to the local session
        verify(localSessionManager).sendToUser(eq(targetUserId), eq(payload));

        // Verify: the pending message was XACK'd after processing
        verify(streamOps).acknowledge(eq(streamKey), eq(groupName), eq(recordId));
    }

    // Feature: redis-message-routing, Property 10: streamKeyFollowsNamingConvention
    // **Validates: Requirements 4.1**
    @Property(tries = 100)
    void streamKeyFollowsNamingConvention(@ForAll("instanceIds") String instanceId) {
        // For any instanceId, the stream key must be exactly "schat:route:" + instanceId
        String streamKey = RedisMessageStreamConsumer.getStreamKey(instanceId);
        String expected = "schat:route:" + instanceId;

        assertEquals(expected, streamKey,
                "Stream key must follow naming convention: schat:route:{instanceId}");

        // Also verify the prefix is correct
        assertTrue(streamKey.startsWith("schat:route:"),
                "Stream key must start with 'schat:route:'");

        // Verify the instanceId can be extracted from the key
        String extractedId = streamKey.substring("schat:route:".length());
        assertEquals(instanceId, extractedId,
                "InstanceId should be extractable from the stream key");
    }

    // Feature: redis-message-routing, Property 11: xtrimKeepsStreamBounded
    // **Validates: Requirements 4.5**
    @Property(tries = 100)
    @SuppressWarnings("unchecked")
    void xtrimKeepsStreamBounded(
            @ForAll("instanceIds") String instanceId,
            @ForAll("maxLens") Long maxLen) {

        // This test verifies that XTRIM is called with the correct MAXLEN parameter,
        // ensuring the stream length stays bounded.

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        LocalSessionManager localSessionManager = mock(LocalSessionManager.class);
        InstanceRegistry instanceRegistry = mock(InstanceRegistry.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(instanceRegistry.getInstanceId()).thenReturn(instanceId);

        SchatProperties props = new SchatProperties();
        props.getInstance().setId(instanceId);
        props.getRedis().getStream().setMaxLen(maxLen);

        String streamKey = "schat:route:" + instanceId;

        // Simulate: XTRIM returns the number of trimmed entries
        long simulatedStreamLength = maxLen + 500;
        long trimmedCount = simulatedStreamLength - maxLen;
        when(streamOps.trim(eq(streamKey), eq(maxLen), eq(true))).thenReturn(trimmedCount);

        // Directly invoke the trim method via reflection to test the trim logic
        RedisMessageStreamConsumer consumer = new RedisMessageStreamConsumer(
                redisTemplate, localSessionManager, instanceRegistry, props, objectMapper, mock(OnlineUserRegistry.class));

        // Use reflection to call the private trimStream method
        try {
            var trimMethod = RedisMessageStreamConsumer.class.getDeclaredMethod("trimStream", String.class);
            trimMethod.setAccessible(true);
            trimMethod.invoke(consumer, streamKey);
        } catch (Exception e) {
            fail("Failed to invoke trimStream: " + e.getMessage());
        }

        // Verify: XTRIM was called with approximate MAXLEN (~maxLen)
        verify(streamOps).trim(eq(streamKey), eq(maxLen), eq(true));

        // The approximate flag (true) means Redis may keep slightly more than maxLen,
        // but the stream length will be bounded at approximately maxLen.
        // After XTRIM MAXLEN ~{maxLen}, stream.length <= maxLen (approximately)
    }
}
