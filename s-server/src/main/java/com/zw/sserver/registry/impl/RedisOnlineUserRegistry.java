package com.zw.sserver.registry.impl;

import com.zw.sserver.registry.OnlineUserRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 基于 Redis 的在线用户注册表实现
 * <p>
 * 使用 Redis Hash (schat:online:users) 存储用户到实例及连接身份的映射。
 * <p>
 * 【补丁 4】使用 StringRedisTemplate 进行所有 Redis 操作，避免 Key 乱码。
 */
@Slf4j
@Component
public class RedisOnlineUserRegistry implements OnlineUserRegistry {

    private static final String ONLINE_USERS_KEY = "schat:online:users";
    private static final DefaultRedisScript<Long> REMOVE_IF_CURRENT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], ARGV[1]) == ARGV[2] then
                return redis.call('HDEL', KEYS[1], ARGV[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisOnlineUserRegistry(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void userOnline(Long userId, String instanceId, String connectionId) {
        redisTemplate.opsForHash().put(ONLINE_USERS_KEY, userId.toString(), owner(instanceId, connectionId));
        log.debug("用户上线: userId={}, instanceId={}", userId, instanceId);
    }

    @Override
    public void userOffline(Long userId, String instanceId, String connectionId) {
        removeIfCurrent(userId.toString(), owner(instanceId, connectionId));
        log.debug("用户下线: userId={}", userId);
    }

    @Override
    public boolean isCurrentConnection(Long userId, String instanceId, String connectionId) {
        return owner(instanceId, connectionId).equals(
                redisTemplate.opsForHash().get(ONLINE_USERS_KEY, userId.toString()));
    }

    @Override
    public String getInstanceId(Long userId) {
        Object value = redisTemplate.opsForHash().get(ONLINE_USERS_KEY, userId.toString());
        return value != null ? instanceOf(value.toString()) : null;
    }

    @Override
    public void cleanByInstance(String instanceId) {
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash()
                .scan(ONLINE_USERS_KEY, ScanOptions.scanOptions().match("*").count(100).build())) {
            while (cursor.hasNext()) {
                Map.Entry<Object, Object> entry = cursor.next();
                String observedOwner = entry.getValue().toString();
                if (instanceId.equals(instanceOf(observedOwner))) {
                    removeIfCurrent(entry.getKey().toString(), observedOwner);
                }
            }
        }
    }

    private void removeIfCurrent(String userId, String observedOwner) {
        redisTemplate.execute(REMOVE_IF_CURRENT, List.of(ONLINE_USERS_KEY), userId, observedOwner);
    }

    private String owner(String instanceId, String connectionId) {
        if (connectionId == null || connectionId.isBlank() || instanceId == null || instanceId.contains("|")) {
            throw new IllegalArgumentException("无效连接身份");
        }
        return instanceId + "|" + connectionId;
    }

    private String instanceOf(String owner) {
        int delimiter = owner.indexOf('|');
        return delimiter < 0 ? owner : owner.substring(0, delimiter);
    }
}
