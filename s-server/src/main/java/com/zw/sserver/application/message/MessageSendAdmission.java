package com.zw.sserver.application.message;

import com.zw.scommon.ratelimit.RedisTokenBucket;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class MessageSendAdmission {

    private final RedisTokenBucket buckets;

    public MessageSendAdmission(StringRedisTemplate redis) {
        this.buckets = new RedisTokenBucket(redis);
    }

    public void check(Long userId) {
        if (!buckets.tryAcquire("schat:rate:message:" + userId, 20, 10.0)) {
            throw new IllegalArgumentException("发送过于频繁，请稍后再试");
        }
    }
}
