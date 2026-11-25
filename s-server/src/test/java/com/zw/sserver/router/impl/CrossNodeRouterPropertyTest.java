package com.zw.sserver.router.impl;

import com.zw.sserver.model.dto.RouteMessage;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.registry.OnlineUserRegistry;
import com.zw.sserver.websocket.LocalSessionManager;
import jakarta.websocket.Session;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CrossNodeRouter 属性测试
 * 使用 jqwik 框架，每个属性测试至少运行 100 次迭代
 */
class CrossNodeRouterPropertyTest {

    // ========== Generators ==========

    @Provide
    Arbitrary<String> messageTypes() {
        return Arbitraries.of("CHAT", "READ", "NOTICE", "KICK_SESSION");
    }

    @Provide
    Arbitrary<Long> userIds() {
        return Arbitraries.longs().between(1L, 1_000_000L);
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

    @Provide
    Arbitrary<String> payloads() {
        return Arbitraries.strings().ofMinLength(1).ofMaxLength(200);
    }

    // ========== Helper ==========

    @SuppressWarnings("unchecked")
    private record TestFixture(
            RedisCrossNodeRouter router,
            LocalSessionManager localSessionManager,
            OnlineUserRegistry onlineUserRegistry,
            InstanceRegistry instanceRegistry,
            StringRedisTemplate stringRedisTemplate,
            StreamOperations<String, Object, Object> streamOps
    ) {}

    @SuppressWarnings("unchecked")
    private TestFixture createFixture() {
        LocalSessionManager localSessionManager = mock(LocalSessionManager.class);
        OnlineUserRegistry onlineUserRegistry = mock(OnlineUserRegistry.class);
        InstanceRegistry instanceRegistry = mock(InstanceRegistry.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);

        lenient().when(stringRedisTemplate.opsForStream()).thenReturn(streamOps);
        lenient().when(instanceRegistry.getInstanceId()).thenReturn("local:8082");
        lenient().when(instanceRegistry.isAlive(anyString())).thenReturn(true);
        lenient().when(streamOps.add(any(StringRecord.class))).thenReturn(
                org.springframework.data.redis.connection.stream.RecordId.of("1-0"));

        RedisCrossNodeRouter router = new RedisCrossNodeRouter(
                localSessionManager, onlineUserRegistry, instanceRegistry,
                stringRedisTemplate);

        return new TestFixture(router, localSessionManager, onlineUserRegistry,
                instanceRegistry, stringRedisTemplate, streamOps);
    }

    private RouteMessage buildMessage(String type, Long targetUserId, String payload, String sourceInstanceId) {
        RouteMessage msg = new RouteMessage();
        msg.setType(type);
        msg.setTargetUserId(targetUserId);
        msg.setPayload(payload);
        msg.setSourceInstanceId(sourceInstanceId);
        return msg;
    }

    // ========== Property Tests ==========

    // Feature: redis-message-routing, Property 6: remoteUserRoutesToCorrectStream
    // **Validates: Requirements 3.1, 4.4, 6.2, 7.2**
    @Property(tries = 100)
    void remoteUserRoutesToCorrectStream(
            @ForAll("messageTypes") String type,
            @ForAll("userIds") Long targetUserId,
            @ForAll("payloads") String payload,
            @ForAll("instanceIds") String sourceInstanceId,
            @ForAll("instanceIds") String targetInstanceId) {

        TestFixture f = createFixture();
        RouteMessage message = buildMessage(type, targetUserId, payload, sourceInstanceId);

        // Target user is NOT local
        when(f.localSessionManager.getSession(targetUserId)).thenReturn(null);
        // Target user is on a remote instance
        when(f.onlineUserRegistry.getInstanceId(targetUserId)).thenReturn(targetInstanceId);

        // Act
        boolean result = f.router.route(message);

        // Assert: message was delivered
        assertTrue(result, "route() should return true for remote user");

        // Assert: XADD was called with the correct stream key
        ArgumentCaptor<StringRecord> captor = ArgumentCaptor.forClass(StringRecord.class);
        verify(f.streamOps).add(captor.capture());

        StringRecord record = captor.getValue();
        String expectedStreamKey = "schat:route:" + targetInstanceId;
        assertEquals(expectedStreamKey, record.getStream(),
                "XADD should target the correct stream for the remote instance");

        // Assert: fields contain the correct data
        Map<String, String> fields = record.getValue();
        assertEquals(type, fields.get("type"));
        assertEquals(targetUserId.toString(), fields.get("targetUserId"));
        assertEquals(payload, fields.get("payload"));
        assertEquals(sourceInstanceId, fields.get("sourceInstanceId"));

        // Assert: no local delivery attempted
        verify(f.localSessionManager, never()).sendToUser(anyLong(), anyString());
    }

    // Feature: redis-message-routing, Property 7: localUserRoutesLocally
    // **Validates: Requirements 3.2, 6.3**
    @Property(tries = 100)
    void localUserRoutesLocally(
            @ForAll("messageTypes") String type,
            @ForAll("userIds") Long targetUserId,
            @ForAll("payloads") String payload,
            @ForAll("instanceIds") String sourceInstanceId) {

        TestFixture f = createFixture();
        RouteMessage message = buildMessage(type, targetUserId, payload, sourceInstanceId);

        // Target user IS local
        Session mockSession = mock(Session.class);
        when(mockSession.getUserProperties()).thenReturn(new java.util.HashMap<>(Map.of("connectionId", "current")));
        when(f.onlineUserRegistry.getInstanceId(targetUserId)).thenReturn("local:8082");
        when(f.onlineUserRegistry.isCurrentConnection(targetUserId, "local:8082", "current")).thenReturn(true);
        when(f.localSessionManager.getSession(targetUserId)).thenReturn(mockSession);
        when(f.localSessionManager.sendToUser(eq(targetUserId), eq(payload))).thenReturn(true);

        // Act
        boolean result = f.router.route(message);

        // Assert: message was delivered locally
        assertTrue(result, "route() should return true for local user");

        // Assert: local delivery was used with the original payload (not the whole RouteMessage)
        verify(f.localSessionManager).sendToUser(eq(targetUserId), eq(payload));

        // Assert: no Stream write occurred
        verify(f.streamOps, never()).add(any(StringRecord.class));
    }

    // Feature: redis-message-routing, Property 8: offlineUserSkipsStream
    // **Validates: Requirements 3.4, 7.3**
    @Property(tries = 100)
    void offlineUserSkipsStream(
            @ForAll("messageTypes") String type,
            @ForAll("userIds") Long targetUserId,
            @ForAll("payloads") String payload,
            @ForAll("instanceIds") String sourceInstanceId) {

        TestFixture f = createFixture();
        RouteMessage message = buildMessage(type, targetUserId, payload, sourceInstanceId);

        // Target user is NOT local
        when(f.localSessionManager.getSession(targetUserId)).thenReturn(null);
        // Target user is offline (no mapping in registry)
        when(f.onlineUserRegistry.getInstanceId(targetUserId)).thenReturn(null);

        // Act
        boolean result = f.router.route(message);

        // Assert: route returns false for offline user
        assertFalse(result, "route() should return false for offline user");

        // Assert: no Stream write occurred
        verify(f.streamOps, never()).add(any(StringRecord.class));

        // Assert: no local delivery attempted
        verify(f.localSessionManager, never()).sendToUser(anyLong(), anyString());
    }
}
