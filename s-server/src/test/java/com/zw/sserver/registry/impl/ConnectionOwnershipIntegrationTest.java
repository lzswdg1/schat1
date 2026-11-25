package com.zw.sserver.registry.impl;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SCHAT_TEST_REDIS_PORT", matches = ".+")
class ConnectionOwnershipIntegrationTest {

    private static LettuceConnectionFactory connections;
    private StringRedisTemplate redis;
    private RedisOnlineUserRegistry registry;

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("SCHAT_TEST_REDIS_PORT")));
        connections.afterPropertiesSet();
        connections.start();
    }

    @BeforeEach
    void reset() {
        redis = new StringRedisTemplate(connections);
        redis.delete("schat:online:users");
        registry = new RedisOnlineUserRegistry(redis);
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    @Test
    void oldNodeDisconnectCannotDeleteNewNodeConnection() {
        registry.userOnline(1L, "node-a:8082", "old");
        registry.userOnline(1L, "node-b:8082", "new");
        registry.userOffline(1L, "node-a:8082", "old");
        assertEquals("node-b:8082", registry.getInstanceId(1L));
        assertTrue(registry.isCurrentConnection(1L, "node-b:8082", "new"));
        assertFalse(registry.isCurrentConnection(1L, "node-a:8082", "old"));
        registry.userOffline(1L, "node-b:8082", "new");
        assertNull(registry.getInstanceId(1L));
    }

    @Test
    void oldConnectionOnSameNodeCannotDeleteReplacement() {
        registry.userOnline(1L, "node-a:8082", "old");
        registry.userOnline(1L, "node-a:8082", "new");
        registry.userOffline(1L, "node-a:8082", "old");
        assertTrue(registry.isCurrentConnection(1L, "node-a:8082", "new"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconnectBetweenScanAndDeletePreservesNewOwner() {
        registry.userOnline(1L, "node-a:8082", "old");
        StringRedisTemplate template = spy(redis);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        Cursor<Map.Entry<Object, Object>> cursor = mock(Cursor.class);
        doReturn(hashes).when(template).opsForHash();
        when(hashes.scan(eq("schat:online:users"), any())).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenAnswer(invocation -> {
            registry.userOnline(1L, "node-a:8082", "new");
            return Map.entry("1", "node-a:8082|old");
        });

        new RedisOnlineUserRegistry(template).cleanByInstance("node-a:8082");

        assertTrue(registry.isCurrentConnection(1L, "node-a:8082", "new"));
        registry.cleanByInstance("node-a:8082");
        assertNull(registry.getInstanceId(1L));
    }
}
