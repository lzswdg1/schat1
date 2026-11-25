package com.zw.sserver.registry.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.config.SchatProperties;
import com.zw.sserver.registry.OnlineUserRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RedisInstanceRegistry 单元测试
 * 使用 Mockito mock StringRedisTemplate 和 OnlineUserRegistry
 */
@ExtendWith(MockitoExtension.class)
class RedisInstanceRegistryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private OnlineUserRegistry onlineUserRegistry;

    private SchatProperties schatProperties;
    private ObjectMapper objectMapper;
    private RedisInstanceRegistry registry;

    @BeforeEach
    void setUp() {
        schatProperties = new SchatProperties();
        schatProperties.getInstance().setId("node-1:8082");
        schatProperties.getHeartbeat().setTimeout(30);
        schatProperties.getHeartbeat().setInterval(10);

        objectMapper = new ObjectMapper();

        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        registry = new RedisInstanceRegistry(redisTemplate, schatProperties, objectMapper);
        // Inject the onlineUserRegistry via reflection since it's @Autowired(required=false)
        try {
            var field = RedisInstanceRegistry.class.getDeclaredField("onlineUserRegistry");
            field.setAccessible(true);
            field.set(registry, onlineUserRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ========== 注册/注销往返测试 ==========

    @Test
    @DisplayName("register() 应写入 Redis Hash 并设置心跳 Key TTL 30s")
    void register_shouldWriteHashAndSetHeartbeatKey() {
        registry.register();

        // 验证 HSET schat:instances node-1:8082 {json}
        verify(hashOperations).put(eq("schat:instances"), eq("node-1:8082"), anyString());

        // 验证 SET schat:instance:heartbeat:node-1:8082 1 EX 30
        verify(valueOperations).set(
                eq("schat:instance:heartbeat:node-1:8082"),
                eq("1"),
                eq(Duration.ofSeconds(30))
        );
    }

    @Test
    @DisplayName("deregister() 应删除 Hash 条目、心跳 Key，并清理用户映射")
    void deregister_shouldCleanUpAllResources() {
        registry.deregister();

        // 验证 HDEL schat:instances node-1:8082
        verify(hashOperations).delete("schat:instances", "node-1:8082");

        // 验证 DEL schat:instance:heartbeat:node-1:8082
        verify(redisTemplate).delete("schat:instance:heartbeat:node-1:8082");

        // 验证调用 cleanByInstance
        verify(onlineUserRegistry).cleanByInstance("node-1:8082");
    }

    @Test
    @DisplayName("register() 后 deregister() 应完整清理（往返一致性）")
    void registerThenDeregister_shouldCleanUpCompletely() {
        registry.register();
        registry.deregister();

        // 验证注册写入
        verify(hashOperations).put(eq("schat:instances"), eq("node-1:8082"), anyString());
        verify(valueOperations).set(
                eq("schat:instance:heartbeat:node-1:8082"),
                eq("1"),
                eq(Duration.ofSeconds(30))
        );

        // 验证注销清理
        verify(hashOperations).delete("schat:instances", "node-1:8082");
        verify(redisTemplate).delete("schat:instance:heartbeat:node-1:8082");
        verify(onlineUserRegistry).cleanByInstance("node-1:8082");
    }

    @Test
    @DisplayName("deregister() 当 onlineUserRegistry 为 null 时不应抛异常")
    void deregister_withNullOnlineUserRegistry_shouldNotThrow() throws Exception {
        // Set onlineUserRegistry to null
        var field = RedisInstanceRegistry.class.getDeclaredField("onlineUserRegistry");
        field.setAccessible(true);
        field.set(registry, null);

        assertDoesNotThrow(() -> registry.deregister());

        verify(hashOperations).delete("schat:instances", "node-1:8082");
        verify(redisTemplate).delete("schat:instance:heartbeat:node-1:8082");
    }

    @Test
    @DisplayName("deregister() 当 cleanByInstance 抛异常时不应传播异常")
    void deregister_whenCleanByInstanceFails_shouldNotPropagate() {
        doThrow(new RuntimeException("Redis error")).when(onlineUserRegistry).cleanByInstance(anyString());

        assertDoesNotThrow(() -> registry.deregister());
    }

    // ========== 心跳 TTL 刷新测试 ==========

    @Test
    @DisplayName("heartbeat() 应刷新心跳 Key 的 TTL 为 30s")
    void heartbeat_shouldRefreshTtl() {
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        registry.heartbeat();

        verify(redisTemplate).expire(
                eq("schat:instance:heartbeat:node-1:8082"),
                eq(Duration.ofSeconds(30))
        );
    }

    @Test
    @DisplayName("heartbeat() 当 Key 不存在时应重新创建")
    void heartbeat_whenKeyMissing_shouldRecreate() {
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(false);

        registry.heartbeat();

        // 验证 expire 被调用
        verify(redisTemplate).expire(
                eq("schat:instance:heartbeat:node-1:8082"),
                eq(Duration.ofSeconds(30))
        );

        // 验证重新创建 Key
        verify(valueOperations).set(
                eq("schat:instance:heartbeat:node-1:8082"),
                eq("1"),
                eq(Duration.ofSeconds(30))
        );
    }

    @Test
    @DisplayName("heartbeat() 使用配置的 timeout 值")
    void heartbeat_shouldUseConfiguredTimeout() {
        schatProperties.getHeartbeat().setTimeout(60);
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        registry.heartbeat();

        verify(redisTemplate).expire(
                eq("schat:instance:heartbeat:node-1:8082"),
                eq(Duration.ofSeconds(60))
        );
    }

    // ========== isAlive 测试 ==========

    @Test
    @DisplayName("isAlive() 当心跳 Key 存在时返回 true")
    void isAlive_whenHeartbeatKeyExists_shouldReturnTrue() {
        when(redisTemplate.hasKey("schat:instance:heartbeat:node-1:8082")).thenReturn(true);

        assertTrue(registry.isAlive("node-1:8082"));
    }

    @Test
    @DisplayName("isAlive() 当心跳 Key 不存在时返回 false")
    void isAlive_whenHeartbeatKeyMissing_shouldReturnFalse() {
        when(redisTemplate.hasKey("schat:instance:heartbeat:node-1:8082")).thenReturn(false);

        assertFalse(registry.isAlive("node-1:8082"));
    }

    // ========== getInstanceId 测试 ==========

    @Test
    @DisplayName("getInstanceId() 应返回配置的实例 ID")
    void getInstanceId_shouldReturnConfiguredId() {
        assertEquals("node-1:8082", registry.getInstanceId());
    }

    // ========== 实例 ID 稳定性校验（补丁 1）==========

    @Test
    @DisplayName("SchatProperties 应拒绝包含 UUID 格式的 instance.id")
    void schatProperties_shouldRejectUuidInstanceId() {
        SchatProperties props = new SchatProperties();
        props.getInstance().setId("550e8400-e29b-41d4-a716-446655440000");

        IllegalStateException ex = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(ex.getMessage().contains("UUID"));
    }

    @Test
    @DisplayName("SchatProperties 应拒绝包含嵌入式 UUID 的 instance.id")
    void schatProperties_shouldRejectEmbeddedUuidInstanceId() {
        SchatProperties props = new SchatProperties();
        props.getInstance().setId("prefix-550e8400-e29b-41d4-a716-446655440000-suffix");

        IllegalStateException ex = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(ex.getMessage().contains("UUID"));
    }

    @Test
    @DisplayName("SchatProperties 应接受 hostname:port 格式的 instance.id")
    void schatProperties_shouldAcceptHostnamePortId() {
        SchatProperties props = new SchatProperties();
        props.getInstance().setId("my-server:8082");

        assertDoesNotThrow(props::validate);
    }

    @Test
    @DisplayName("SchatProperties 应接受 IP:port 格式的 instance.id")
    void schatProperties_shouldAcceptIpPortId() {
        SchatProperties props = new SchatProperties();
        props.getInstance().setId("192.168.1.10:8082");

        assertDoesNotThrow(props::validate);
    }

    @Test
    @DisplayName("SchatProperties 应接受 K8s Pod Name 格式的 instance.id")
    void schatProperties_shouldAcceptK8sPodNameId() {
        SchatProperties props = new SchatProperties();
        props.getInstance().setId("schat-server-0");

        assertDoesNotThrow(props::validate);
    }

    @Test
    @DisplayName("SchatProperties 应拒绝空白的 instance.id")
    void schatProperties_shouldRejectBlankInstanceId() {
        SchatProperties props = new SchatProperties();
        props.getInstance().setId("   ");

        IllegalStateException ex = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(ex.getMessage().contains("不能为空"));
    }

    @Test
    @DisplayName("SchatProperties 应拒绝 null 的 instance.id")
    void schatProperties_shouldRejectNullInstanceId() {
        SchatProperties props = new SchatProperties();
        props.getInstance().setId(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(ex.getMessage().contains("不能为空"));
    }

    // ========== register() JSON 内容验证 ==========

    @Test
    @DisplayName("register() 写入的 JSON 应包含 instanceId 字段")
    void register_shouldWriteJsonWithInstanceId() {
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);

        registry.register();

        verify(hashOperations).put(eq("schat:instances"), eq("node-1:8082"), jsonCaptor.capture());
        String json = jsonCaptor.getValue();
        assertTrue(json.contains("\"instanceId\":\"node-1:8082\""));
        assertTrue(json.contains("\"startTime\""));
    }
}
