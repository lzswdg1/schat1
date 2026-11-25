package com.zw.sserver.registry.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.config.SchatProperties;
import com.zw.sserver.registry.OnlineUserRegistry;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * InstanceRegistry 属性测试
 * 使用 jqwik 框架，每个属性测试至少运行 100 次迭代
 */
class InstanceRegistryPropertyTest {

    // ========== Generators ==========

    /**
     * 生成合法的 instanceId（hostname:port 或 IP:port 格式）
     * 排除 UUID 格式
     */
    @Provide
    Arbitrary<String> validInstanceIds() {
        Arbitrary<String> hostnamePort = Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(15),
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

    /**
     * 生成包含 UUID 格式的 instanceId（应被拒绝）
     */
    @Provide
    Arbitrary<String> uuidInstanceIds() {
        return Arbitraries.of(
                "550e8400-e29b-41d4-a716-446655440000",
                "prefix-123e4567-e89b-12d3-a456-426614174000",
                "node-a1b2c3d4-e5f6-7890-abcd-ef1234567890-suffix"
        );
    }

    // ========== Helper ==========

    private record TestFixture(
            RedisInstanceRegistry registry,
            StringRedisTemplate redisTemplate,
            HashOperations<String, Object, Object> hashOps,
            ValueOperations<String, String> valueOps,
            OnlineUserRegistry onlineUserRegistry,
            SchatProperties properties
    ) {}

    @SuppressWarnings("unchecked")
    private TestFixture createFixture(String instanceId) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        OnlineUserRegistry onlineUserRegistry = mock(OnlineUserRegistry.class);

        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);

        SchatProperties props = new SchatProperties();
        props.getInstance().setId(instanceId);
        props.getHeartbeat().setTimeout(30);

        ObjectMapper objectMapper = new ObjectMapper();
        RedisInstanceRegistry registry = new RedisInstanceRegistry(redisTemplate, props, objectMapper);

        // Inject onlineUserRegistry
        try {
            var field = RedisInstanceRegistry.class.getDeclaredField("onlineUserRegistry");
            field.setAccessible(true);
            field.set(registry, onlineUserRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return new TestFixture(registry, redisTemplate, hashOps, valueOps, onlineUserRegistry, props);
    }

    // ========== Property Tests ==========

    // Feature: redis-message-routing, Property 1: registerThenDeregisterCleansUp
    // **Validates: Requirements 1.1, 1.3, 9.4**
    @Property(tries = 100)
    void registerThenDeregisterCleansUp(@ForAll("validInstanceIds") String instanceId) {
        TestFixture f = createFixture(instanceId);

        // Act: register then deregister
        f.registry.register();
        f.registry.deregister();

        // Assert: register wrote to Hash and set heartbeat key
        verify(f.hashOps).put(eq("schat:instances"), eq(instanceId), anyString());
        verify(f.valueOps).set(
                eq("schat:instance:heartbeat:" + instanceId),
                eq("1"),
                eq(Duration.ofSeconds(30))
        );

        // Assert: deregister cleaned up Hash, heartbeat key, and user mappings
        verify(f.hashOps).delete("schat:instances", instanceId);
        verify(f.redisTemplate).delete("schat:instance:heartbeat:" + instanceId);
        verify(f.onlineUserRegistry).cleanByInstance(instanceId);
    }

    // Feature: redis-message-routing, Property 2: heartbeatRefreshesTtl
    // **Validates: Requirements 1.2**
    @Property(tries = 100)
    void heartbeatRefreshesTtl(@ForAll("validInstanceIds") String instanceId) {
        TestFixture f = createFixture(instanceId);
        when(f.redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        // Act
        f.registry.heartbeat();

        // Assert: heartbeat refreshes TTL to 30s
        verify(f.redisTemplate).expire(
                eq("schat:instance:heartbeat:" + instanceId),
                eq(Duration.ofSeconds(30))
        );
    }

    // Feature: redis-message-routing, Property 3: expiredInstanceCleansUsers
    // **Validates: Requirements 1.4**
    @Property(tries = 100)
    void expiredInstanceCleansUsers(@ForAll("validInstanceIds") String instanceId) {
        TestFixture f = createFixture(instanceId);

        // Simulate: instance heartbeat key has expired (isAlive returns false)
        when(f.redisTemplate.hasKey("schat:instance:heartbeat:" + instanceId)).thenReturn(false);

        // Verify the instance is detected as not alive
        assertFalse(f.registry.isAlive(instanceId));

        // When deregister is called (as would happen on expiry detection),
        // it should clean up user mappings
        f.registry.deregister();

        verify(f.onlineUserRegistry).cleanByInstance(instanceId);
        verify(f.hashOps).delete("schat:instances", instanceId);
        verify(f.redisTemplate).delete("schat:instance:heartbeat:" + instanceId);
    }

    // Feature: redis-message-routing, Property 20: instanceIdStableAcrossRestarts
    // **Validates: Requirements 3.6, 补丁 1**
    @Property(tries = 100)
    void instanceIdStableAcrossRestarts(@ForAll("validInstanceIds") String instanceId) {
        // Simulate two "restarts" with the same configuration
        TestFixture f1 = createFixture(instanceId);
        TestFixture f2 = createFixture(instanceId);

        // The instanceId should be identical across both "restarts"
        String id1 = f1.registry.getInstanceId();
        String id2 = f2.registry.getInstanceId();

        assertEquals(id1, id2,
                "InstanceId must be stable across restarts for the same configuration");
        assertEquals(instanceId, id1,
                "InstanceId must match the configured value");

        // Additionally verify that UUID-format IDs are rejected by SchatProperties
        // (this is the validation side of 补丁 1)
        SchatProperties propsWithUuid = new SchatProperties();
        propsWithUuid.getInstance().setId("550e8400-e29b-41d4-a716-446655440000");
        assertThrows(IllegalStateException.class, propsWithUuid::validate,
                "UUID-format instanceId should be rejected by SchatProperties validation");
    }
}
