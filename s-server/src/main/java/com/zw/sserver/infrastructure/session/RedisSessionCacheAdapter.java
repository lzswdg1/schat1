package com.zw.sserver.infrastructure.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.domain.session.service.SessionCachePort;
import com.zw.sserver.model.vo.SessionVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Redis 会话列表缓存适配器。
 */
@Component
public class RedisSessionCacheAdapter implements SessionCachePort {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionCacheAdapter.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String KEY_PREFIX = "schat:session:list:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final JavaType sessionListType;

    public RedisSessionCacheAdapter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.sessionListType = objectMapper.getTypeFactory().constructCollectionType(List.class, SessionVO.class);
    }

    @Override
    public List<SessionVO> get(Long userId) {
        if (userId == null) {
            return null;
        }
        String cached;
        try {
            cached = redisTemplate.opsForValue().get(key(userId));
        } catch (Exception e) {
            log.warn("读取会话缓存失败，降级查库: userId={}", userId, e);
            return null;
        }
        if (cached == null || cached.isBlank()) return null;
        try {
            return objectMapper.readValue(cached, sessionListType);
        } catch (JsonProcessingException e) {
            log.warn("会话缓存反序列化失败，清理缓存: userId={}", userId, e);
            evict(userId);
            return null;
        }
    }

    @Override
    public void put(Long userId, List<SessionVO> sessions) {
        if (userId == null || sessions == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(userId), objectMapper.writeValueAsString(sessions), CACHE_TTL);
        } catch (JsonProcessingException e) {
            log.warn("会话缓存序列化失败: userId={}", userId, e);
        } catch (Exception e) {
            log.warn("写入会话缓存失败: userId={}", userId, e);
        }
    }

    @Override
    public void evict(Long userId) {
        if (userId != null) {
            try {
                redisTemplate.delete(key(userId));
            } catch (Exception e) {
                log.warn("清理会话缓存失败: userId={}", userId, e);
            }
        }
    }

    @Override
    public void evictAll(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        for (Long userId : userIds) {
            evict(userId);
        }
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
