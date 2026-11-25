package com.zw.sserver.infrastructure.message;

import com.zw.sserver.application.session.SessionCacheInvalidationService;
import com.zw.sserver.domain.message.service.MessageDeliveryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

@Component
public class MessageOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(MessageOutboxPublisher.class);
    private final JdbcMessageOutbox outbox;
    private final MessageDeliveryPort delivery;
    private final SessionCacheInvalidationService cacheInvalidation;
    private final DeliveryEnvelope envelopes;
    private final StringRedisTemplate redis;
    private boolean redisUnavailable;

    public MessageOutboxPublisher(JdbcMessageOutbox outbox, MessageDeliveryPort delivery,
                                  SessionCacheInvalidationService cacheInvalidation, DeliveryEnvelope envelopes,
                                  StringRedisTemplate redis) {
        this.outbox = outbox;
        this.delivery = delivery;
        this.cacheInvalidation = cacheInvalidation;
        this.envelopes = envelopes;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${schat.outbox.poll-interval-ms:100}")
    public void publish() {
        if (!redisReady()) return;
        for (JdbcMessageOutbox.Entry entry : outbox.claim(100)) {
            try {
                cacheInvalidation.evictUser(entry.targetUserId());
                if (entry.payload() != null && !delivery.deliverPrivate(entry.targetUserId(), envelopes.stamp(entry))) {
                    throw new IllegalStateException("用户离线或路由投递失败");
                }
                outbox.complete(entry);
            } catch (Exception exception) {
                outbox.retry(entry, exception.getMessage());
                log.warn("Outbox 投递失败: id={}, attempts={}", entry.id(), entry.attempts(), exception);
            }
        }
    }

    private boolean redisReady() {
        try {
            String response = redis.execute((RedisCallback<String>) connection -> connection.ping());
            if ("PONG".equals(response)) {
                if (redisUnavailable) log.info("outbox_redis_available");
                redisUnavailable = false;
                return true;
            }
        } catch (RuntimeException exception) {
            if (!redisUnavailable) log.warn("outbox_redis_unavailable errorType={}", exception.getClass().getSimpleName());
            redisUnavailable = true;
            return false;
        }
        if (!redisUnavailable) log.warn("outbox_redis_unavailable reason=NO_PONG");
        redisUnavailable = true;
        return false;
    }
}
