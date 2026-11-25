package com.zw.sserver.registry.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.config.SchatProperties;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.registry.OnlineUserRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 Redis 的实例注册与发现实现
 * <p>
 * 使用 Redis Hash (schat:instances) 存储实例信息，
 * 辅助 Key (schat:instance:heartbeat:{instanceId}) 控制心跳 TTL。
 * <p>
 * 【补丁 1】getInstanceId() 返回配置的物理稳定 ID，启动时由 SchatProperties 验证 ID 格式。
 * 【补丁 4】使用 StringRedisTemplate 进行所有 Redis 操作，避免 Key 乱码。
 */
@Slf4j
@Component
public class RedisInstanceRegistry implements InstanceRegistry {

    private static final String INSTANCES_KEY = "schat:instances";
    private static final String HEARTBEAT_KEY_PREFIX = "schat:instance:heartbeat:";

    private final StringRedisTemplate redisTemplate;
    private final SchatProperties schatProperties;
    private final ObjectMapper objectMapper;

    /**
     * OnlineUserRegistry 尚未创建，使用 @Autowired(required = false) 避免启动失败。
     * 当 OnlineUserRegistry 实现可用时会自动注入。
     */
    @Autowired(required = false)
    private OnlineUserRegistry onlineUserRegistry;

    public RedisInstanceRegistry(StringRedisTemplate redisTemplate,
                                 SchatProperties schatProperties,
                                 ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.schatProperties = schatProperties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        register();
    }

    @PreDestroy
    public void destroy() {
        deregister();
    }

    @Override
    public void register() {
        String instanceId = getInstanceId();
        String heartbeatKey = HEARTBEAT_KEY_PREFIX + instanceId;
        int timeoutSeconds = schatProperties.getHeartbeat().getTimeout();

        try {
            // 构建实例信息 JSON
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("instanceId", instanceId);
            info.put("ip", getLocalIp());
            info.put("port", getPort());
            info.put("startTime", System.currentTimeMillis());

            String infoJson = objectMapper.writeValueAsString(info);

            // HSET schat:instances {instanceId} {JSON info}
            redisTemplate.opsForHash().put(INSTANCES_KEY, instanceId, infoJson);

            // SET schat:instance:heartbeat:{instanceId} 1 EX 30
            redisTemplate.opsForValue().set(heartbeatKey, "1", Duration.ofSeconds(timeoutSeconds));

            log.info("实例注册成功: instanceId={}, heartbeatTTL={}s", instanceId, timeoutSeconds);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("实例信息序列化失败", e);
        }
    }

    @Override
    @Scheduled(fixedDelayString = "${schat.heartbeat.interval:10}000")
    public void heartbeat() {
        String instanceId = getInstanceId();
        String heartbeatKey = HEARTBEAT_KEY_PREFIX + instanceId;
        int timeoutSeconds = schatProperties.getHeartbeat().getTimeout();

        Boolean result = redisTemplate.expire(heartbeatKey, Duration.ofSeconds(timeoutSeconds));
        if (Boolean.TRUE.equals(result)) {
            log.debug("心跳续期成功: instanceId={}, TTL={}s", instanceId, timeoutSeconds);
        } else {
            // Key 不存在，可能是首次心跳或 Key 已过期，重新设置
            redisTemplate.opsForValue().set(heartbeatKey, "1", Duration.ofSeconds(timeoutSeconds));
            log.warn("心跳 Key 不存在，已重新创建: instanceId={}", instanceId);
        }
    }

    @Override
    public void deregister() {
        String instanceId = getInstanceId();
        String heartbeatKey = HEARTBEAT_KEY_PREFIX + instanceId;

        // HDEL schat:instances {instanceId}
        redisTemplate.opsForHash().delete(INSTANCES_KEY, instanceId);

        // DEL heartbeat key
        redisTemplate.delete(heartbeatKey);

        // 清理关联用户映射
        if (onlineUserRegistry != null) {
            try {
                onlineUserRegistry.cleanByInstance(instanceId);
                log.info("已清理实例关联的用户映射: instanceId={}", instanceId);
            } catch (Exception e) {
                log.error("清理实例关联用户映射失败: instanceId={}", instanceId, e);
            }
        }

        log.info("实例注销完成: instanceId={}", instanceId);
    }

    @Override
    public String getInstanceId() {
        return schatProperties.getInstance().getId();
    }

    @Override
    public boolean isAlive(String instanceId) {
        String heartbeatKey = HEARTBEAT_KEY_PREFIX + instanceId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(heartbeatKey));
    }

    private String getLocalIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            log.warn("获取本机 IP 失败，使用 127.0.0.1", e);
            return "127.0.0.1";
        }
    }

    private int getPort() {
        // 从 instanceId 中解析端口（格式: hostname:port）
        String instanceId = getInstanceId();
        int colonIndex = instanceId.lastIndexOf(':');
        if (colonIndex > 0 && colonIndex < instanceId.length() - 1) {
            try {
                return Integer.parseInt(instanceId.substring(colonIndex + 1));
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return 8082; // 默认端口
    }
}
