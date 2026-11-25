package com.zw.scommon.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

public class RedisTokenBucket {

    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
            local tokens = tonumber(state[1]) or capacity
            local updated = tonumber(state[2]) or now
            tokens = math.min(capacity, tokens + math.max(0, now - updated) * rate / 1000)
            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated', math.max(now, updated))
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return allowed
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisTokenBucket(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean tryAcquire(String key, int capacity, double permitsPerSecond) {
        if (capacity < 1 || !Double.isFinite(permitsPerSecond) || permitsPerSecond <= 0) {
            throw new IllegalArgumentException("Invalid token bucket configuration");
        }
        long ttlMillis = Math.max(1000L, (long) Math.ceil(capacity / permitsPerSecond * 2000));
        Long allowed = redis.execute(ACQUIRE, List.of(key), String.valueOf(capacity),
                String.valueOf(permitsPerSecond), String.valueOf(ttlMillis));
        return Long.valueOf(1).equals(allowed);
    }
}
