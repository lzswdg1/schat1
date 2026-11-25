package com.zw.sserver.application.session;

import com.zw.sserver.domain.session.service.SessionCachePort;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 会话缓存失效应用服务。
 */
@Service
public class SessionCacheInvalidationService {

    private final SessionCachePort sessionCachePort;

    public SessionCacheInvalidationService(SessionCachePort sessionCachePort) {
        this.sessionCachePort = sessionCachePort;
    }

    public void evictUser(Long userId) {
        if (userId != null) {
            sessionCachePort.evict(userId);
        }
    }

    public void evictPrivateConversation(Long userA, Long userB) {
        sessionCachePort.evictAll(List.of(userA, userB));
    }

    public void evictGroupMembers(Collection<Long> memberIds) {
        sessionCachePort.evictAll(memberIds);
    }
}
