package com.zw.sserver.websocket.impl;

import com.zw.sserver.websocket.LocalSessionManager;
import com.zw.sserver.websocket.WebSocketSender;
import com.zw.sserver.infrastructure.message.DeliveryEnvelope;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link LocalSessionManager} 的默认实现。
 * <p>
 * 使用 {@link ConcurrentHashMap} 管理本地 WebSocket Session，
 * 并通过连接自身的串行发送状态保证
 * {@link #sendToUser(Long, String)} 的线程安全。
 */
@Component
public class DefaultLocalSessionManager implements LocalSessionManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultLocalSessionManager.class);

    private final ConcurrentHashMap<Long, Session> sessions = new ConcurrentHashMap<>();
    private final DeliveryEnvelope envelopes;

    public DefaultLocalSessionManager(DeliveryEnvelope envelopes) {
        this.envelopes = envelopes;
    }

    @Override
    public void addSession(Long userId, Session session) {
        WebSocketSender.initialize(session);
        sessions.put(userId, session);
    }

    @Override
    public void removeSession(Long userId, Session session) {
        sessions.remove(userId, session);
    }

    @Override
    public Session getSession(Long userId) {
        return sessions.get(userId);
    }

    @Override
    public boolean sendToUser(Long userId, String message) {
        Session session = sessions.get(userId);
        return WebSocketSender.send(session, message,
                () -> sessions.get(userId) == session && envelopes.maySend(userId, message));
    }

    @Override
    public Set<Long> getAllLocalUsers() {
        return sessions.keySet();
    }

    @Override
    public void closeAll(String reason) {
        for (Map.Entry<Long, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            try {
                if (session.isOpen()) {
                    session.close(new CloseReason(CloseReason.CloseCodes.SERVICE_RESTART, reason));
                }
            } catch (IOException e) {
                log.error("关闭 Session 失败: userId={}", entry.getKey(), e);
            }
        }
    }
}
