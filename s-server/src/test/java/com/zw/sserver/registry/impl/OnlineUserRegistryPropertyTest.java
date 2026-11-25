package com.zw.sserver.registry.impl;

import net.jqwik.api.*;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OnlineUserRegistryPropertyTest {

    @Property(tries = 100)
    void routeLookupDecodesConnectionOwner(@ForAll long userId, @ForAll long connectionNumber) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        String connectionId = "connection-" + connectionNumber;
        when(hashes.get("schat:online:users", Long.toString(userId))).thenReturn("node:8082|" + connectionId);
        RedisOnlineUserRegistry registry = new RedisOnlineUserRegistry(redis);

        registry.userOnline(userId, "node:8082", connectionId);
        assertEquals("node:8082", registry.getInstanceId(userId));
        assertTrue(registry.isCurrentConnection(userId, "node:8082", connectionId));
        assertFalse(registry.isCurrentConnection(userId, "node:8082", connectionId + "-old"));
        verify(hashes).put("schat:online:users", Long.toString(userId), "node:8082|" + connectionId);
    }

    @Property(tries = 100)
    void disconnectIncludesExpectedInstanceAndConnection(@ForAll long userId, @ForAll long connectionNumber) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisOnlineUserRegistry registry = new RedisOnlineUserRegistry(redis);
        String connectionId = "connection-" + connectionNumber;
        registry.userOffline(userId, "node:8082", connectionId);
        verify(redis).execute(any(), eq(java.util.List.of("schat:online:users")),
                eq(Long.toString(userId)), eq("node:8082|" + connectionId));
        verify(redis, never()).opsForHash();
    }
}
