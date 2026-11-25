package com.zw.sserver.infrastructure.message;

import com.zw.sserver.domain.message.service.OfflineInboxPort;
import com.zw.sserver.mq.RedisInboxService;
import org.springframework.stereotype.Component;

/**
 * Redis 离线收件箱适配器。
 */
@Component
public class RedisOfflineInboxAdapter implements OfflineInboxPort {

    private final RedisInboxService redisInboxService;

    public RedisOfflineInboxAdapter(RedisInboxService redisInboxService) {
        this.redisInboxService = redisInboxService;
    }

    @Override
    public void add(Long userId, Long messageId, String briefContent, long timestamp) {
        redisInboxService.addToInbox(userId, messageId, briefContent, timestamp);
    }
}
