package com.zw.suser.ratelimit;

import com.zw.scommon.ratelimit.RedisTokenBucket;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {

    private final RedisTokenBucket buckets;

    public LoginRateLimiter(StringRedisTemplate redis) {
        this.buckets = new RedisTokenBucket(redis);
    }

    public boolean tryAcquire(String ip) {
        return buckets.tryAcquire("schat:rate:login:" + ip, 5, 1.0);
    }
}
