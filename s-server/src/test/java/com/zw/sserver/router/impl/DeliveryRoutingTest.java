package com.zw.sserver.router.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.config.SchatProperties;
import com.zw.sserver.model.dto.RouteMessage;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.registry.OnlineUserRegistry;
import com.zw.sserver.websocket.LocalSessionManager;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryRoutingTest {

    @Test
    @SuppressWarnings("unchecked")
    void deadNodeIsNotTreatedAsSuccessfulPublication() {
        var sessions = mock(LocalSessionManager.class);
        var online = mock(OnlineUserRegistry.class);
        var instances = mock(InstanceRegistry.class);
        var redis = mock(StringRedisTemplate.class);
        when(online.getInstanceId(2L)).thenReturn("dead:8082");
        when(instances.getInstanceId()).thenReturn("local:8082");
        RouteMessage message = message();
        assertFalse(new RedisCrossNodeRouter(sessions, online, instances, redis).route(message));
        verifyNoInteractions(redis);
        verify(sessions, never()).sendToUser(anyLong(), anyString());
    }

    @Test
    void supersededLocalConnectionCannotReceiveNewPublication() {
        var sessions = mock(LocalSessionManager.class);
        var online = mock(OnlineUserRegistry.class);
        var instances = mock(InstanceRegistry.class);
        var redis = mock(StringRedisTemplate.class);
        Session session = mock(Session.class);
        when(session.getUserProperties()).thenReturn(new HashMap<>(Map.of("connectionId", "old")));
        when(sessions.getSession(2L)).thenReturn(session);
        when(online.getInstanceId(2L)).thenReturn("local:8082");
        when(instances.getInstanceId()).thenReturn("local:8082");
        assertFalse(new RedisCrossNodeRouter(sessions, online, instances, redis).route(message()));
        verify(sessions, never()).sendToUser(anyLong(), anyString());
        verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queuedEventForAnOldConnectionIsTransportAcknowledgedButNeverSentToThatConnection() throws Exception {
        var sessions = mock(LocalSessionManager.class);
        var online = mock(OnlineUserRegistry.class);
        var instances = mock(InstanceRegistry.class);
        var redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(instances.getInstanceId()).thenReturn("old-node:8082");
        Session session = mock(Session.class);
        when(session.getUserProperties()).thenReturn(new HashMap<>(Map.of("connectionId", "old")));
        when(sessions.getSession(2L)).thenReturn(session);
        var consumer = new RedisMessageStreamConsumer(redis, sessions, instances, new SchatProperties(),
                new ObjectMapper(), online);
        Map<Object, Object> fields = Map.of("type", "CHAT", "targetUserId", "2", "payload", "payload");
        var record = MapRecord.create("stream", fields);
        var process = RedisMessageStreamConsumer.class.getDeclaredMethod("processRecord", String.class, String.class, MapRecord.class);
        process.setAccessible(true);
        process.invoke(consumer, "stream", "group", record);
        verify(sessions, never()).sendToUser(anyLong(), anyString());
        verify(streams).acknowledge(eq("stream"), eq("group"), any(org.springframework.data.redis.connection.stream.RecordId.class));
    }

    private RouteMessage message() {
        RouteMessage message = new RouteMessage();
        message.setType("CHAT");
        message.setTargetUserId(2L);
        message.setPayload("payload");
        return message;
    }
}
