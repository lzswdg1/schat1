package com.zw.sserver.websocket;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

public final class WebSocketSender {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSender.class);
    private static final String STATE_KEY = WebSocketSender.class.getName() + ".state";
    private static final long LOCK_WAIT_MILLIS = 500;
    private static final long WRITE_TIMEOUT_MILLIS = 2000;
    private static final int MAX_PENDING_SENDS = 32;

    private WebSocketSender() {
    }

    public static void initialize(Session session) {
        state(session);
    }

    private static OutboundState state(Session session) {
        synchronized (session) {
            var properties = session.getUserProperties();
            OutboundState state = (OutboundState) properties.get(STATE_KEY);
            if (state == null) {
                state = new OutboundState();
                properties.put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", WRITE_TIMEOUT_MILLIS);
                properties.put("org.apache.tomcat.websocket.SESSION_CLOSE_TIMEOUT", WRITE_TIMEOUT_MILLIS);
                properties.put(STATE_KEY, state);
            }
            return state;
        }
    }

    public static boolean send(Session session, String text) {
        return send(session, text, () -> true);
    }

    public static boolean send(Session session, String text, BooleanSupplier maySend) {
        if (session == null || !session.isOpen()) return false;
        OutboundState state;
        try {
            state = state(session);
        } catch (IllegalStateException exception) {
            return false;
        }
        if (!state.slots.tryAcquire()) return false;
        boolean locked = false;
        try {
            locked = state.lock.tryLock(LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            if (!locked || state.failed || !session.isOpen() || !maySend.getAsBoolean()) return false;
            try {
                session.getBasicRemote().sendText(text);
                return true;
            } catch (IOException | IllegalStateException exception) {
                state.failed = true;
                log.warn("WebSocket 发送失败，关闭连接: sessionId={}", session.getId(), exception);
                try {
                    session.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "WebSocket write failed"));
                } catch (IOException | IllegalStateException closeException) {
                    log.debug("WebSocket 发送失败后关闭连接失败", closeException);
                }
                return false;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (locked) state.lock.unlock();
            state.slots.release();
        }
    }

    private static final class OutboundState {
        private final ReentrantLock lock = new ReentrantLock(true);
        private final Semaphore slots = new Semaphore(MAX_PENDING_SENDS);
        private boolean failed;
    }
}
