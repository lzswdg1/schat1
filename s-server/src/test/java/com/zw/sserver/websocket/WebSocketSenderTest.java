package com.zw.sserver.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.application.message.SendGroupMessageUseCase;
import com.zw.sserver.application.message.SendMessageResult;
import com.zw.sserver.application.message.SendPrivateMessageUseCase;
import com.zw.sserver.infrastructure.message.DeliveryEnvelope;
import com.zw.sserver.model.dto.ChatMsgDTO;
import com.zw.sserver.websocket.handler.ChatMessageHandler;
import com.zw.sserver.websocket.handler.GroupMessageHandler;
import com.zw.sserver.websocket.handler.GroupReadReceiptHandler;
import com.zw.sserver.websocket.handler.HeartbeatHandler;
import com.zw.sserver.websocket.impl.DefaultLocalSessionManager;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketSenderTest {

    private Session session;
    private RemoteEndpoint.Basic remote;
    private Map<String, Object> properties;

    @BeforeEach
    void setup() {
        session = mock(Session.class);
        remote = mock(RemoteEndpoint.Basic.class);
        properties = new ConcurrentHashMap<>();
        when(session.getUserProperties()).thenReturn(properties);
        when(session.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(remote);
    }

    private <T> T stateField(String fieldName, Class<T> type) throws Exception {
        WebSocketSender.initialize(session);
        Object state = properties.get(WebSocketSender.class.getName() + ".state");
        var field = state.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return type.cast(field.get(state));
    }

    @Test
    void allTextWritersSerializeWithPushesOnTheSameActualSession() throws Exception {
        var envelopes = mock(DeliveryEnvelope.class);
        when(envelopes.maySend(anyLong(), anyString())).thenReturn(true);
        var manager = new DefaultLocalSessionManager(envelopes);
        manager.addSession(1L, session);
        var mapper = new ObjectMapper();
        var privateSend = mock(SendPrivateMessageUseCase.class);
        var groupSend = mock(SendGroupMessageUseCase.class);
        when(privateSend.execute(any())).thenReturn(SendMessageResult.success("private", 1L, 1L));
        when(groupSend.execute(any())).thenReturn(SendMessageResult.success("group", 2L, 1L));
        var endpointSend = ChatEndpoint.class.getDeclaredMethod("safeSend", Session.class, String.class);
        endpointSend.setAccessible(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        doAnswer(invocation -> {
            int count = active.incrementAndGet();
            maximum.accumulateAndGet(count, Math::max);
            try {
                if ("push".equals(invocation.getArgument(0))) {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }
            } finally {
                active.decrementAndGet();
            }
            return null;
        }).when(remote).sendText(anyString());
        var workers = Executors.newFixedThreadPool(6);
        try {
            var push = workers.submit(() -> manager.sendToUser(1L, "push"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            List<Callable<Boolean>> writers = List.of(
                    () -> { new HeartbeatHandler().handle(session, 1L, new ChatMsgDTO(), "{}"); return true; },
                    () -> { new ChatMessageHandler(mapper, privateSend).handle(session, 1L, new ChatMsgDTO(), "{}"); return true; },
                    () -> { new GroupMessageHandler(mapper, groupSend).handle(session, 1L, new ChatMsgDTO(), "{}"); return true; },
                    () -> { new GroupReadReceiptHandler(null, null, null).handle(session, 1L, new ChatMsgDTO(), "{}"); return true; },
                    () -> { endpointSend.invoke(new ChatEndpoint(), session, "endpoint-error"); return true; });
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (var writer : writers) results.add(workers.submit(writer));
            ReentrantLock lock = stateField("lock", ReentrantLock.class);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(350);
            while (lock.getQueueLength() < writers.size() && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(writers.size(), lock.getQueueLength());
            assertEquals(1, maximum.get());
            release.countDown();
            assertTrue(push.get(3, TimeUnit.SECONDS));
            for (var result : results) assertTrue(result.get(3, TimeUnit.SECONDS));
            assertEquals(1, maximum.get());
            verify(remote, times(6)).sendText(anyString());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void excessPendingCallsAreRejectedAndSlotsAreReusable() throws Exception {
        Semaphore slots = stateField("slots", Semaphore.class);
        assertTrue(slots.tryAcquire(32));
        try {
            assertFalse(WebSocketSender.send(session, "overflow"));
            verifyNoInteractions(remote);
        } finally {
            slots.release(32);
        }
        assertTrue(WebSocketSender.send(session, "after-pressure"));
        assertEquals(32, slots.availablePermits());
    }

    @Test
    void lockWaitIsBoundedAndTimeoutDoesNotLeakCapacity() throws Exception {
        ReentrantLock lock = stateField("lock", ReentrantLock.class);
        var worker = Executors.newSingleThreadExecutor();
        lock.lock();
        try {
            assertFalse(worker.submit(() -> WebSocketSender.send(session, "blocked")).get(3, TimeUnit.SECONDS));
            verifyNoInteractions(remote);
            assertEquals(32, stateField("slots", Semaphore.class).availablePermits());
        } finally {
            lock.unlock();
            worker.shutdownNow();
        }
        assertTrue(WebSocketSender.send(session, "after-timeout"));
    }

    @Test
    void interruptionPreservesTheInterruptFlagAndReleasesCapacity() throws Exception {
        ReentrantLock lock = stateField("lock", ReentrantLock.class);
        var interrupted = new AtomicBoolean();
        var result = new AtomicBoolean(true);
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            result.set(WebSocketSender.send(session, "interrupted"));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        lock.lock();
        try {
            worker.start();
            worker.join(3000);
            assertFalse(worker.isAlive());
            assertFalse(result.get());
            assertTrue(interrupted.get());
            assertEquals(32, stateField("slots", Semaphore.class).availablePermits());
            verifyNoInteractions(remote);
        } finally {
            lock.unlock();
        }
    }

    @Test
    void failedDeliveryGuardNeverWritesAndReleasesTheLock() throws Exception {
        assertFalse(WebSocketSender.send(session, "revoked", () -> false));
        verifyNoInteractions(remote);
        assertTrue(WebSocketSender.send(session, "allowed"));
    }

    @Test
    void aDeliveryRevokedWhileWaitingIsRecheckedInsideTheSendLock() throws Exception {
        ReentrantLock lock = stateField("lock", ReentrantLock.class);
        var allowed = new AtomicBoolean(true);
        var worker = Executors.newSingleThreadExecutor();
        lock.lock();
        try {
            var waiting = worker.submit(() -> WebSocketSender.send(session, "revoked", allowed::get));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(350);
            while (!lock.hasQueuedThreads() && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(lock.hasQueuedThreads());
            allowed.set(false);
            lock.unlock();
            assertFalse(waiting.get(3, TimeUnit.SECONDS));
            verifyNoInteractions(remote);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
            worker.shutdownNow();
        }
    }

    @Test
    void closingBetweenOpenCheckAndStateLookupReturnsFailureWithoutEscaping() {
        when(session.getUserProperties()).thenThrow(new IllegalStateException("closed"));
        assertDoesNotThrow(() -> assertFalse(WebSocketSender.send(session, "closed")));
        verifyNoInteractions(remote);
    }

    @Test
    void closedSessionAfterWaitingIsNotWritten() throws Exception {
        ReentrantLock lock = stateField("lock", ReentrantLock.class);
        var worker = Executors.newSingleThreadExecutor();
        lock.lock();
        try {
            var waiting = worker.submit(() -> WebSocketSender.send(session, "closed"));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(350);
            while (!lock.hasQueuedThreads() && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(lock.hasQueuedThreads());
            when(session.isOpen()).thenReturn(false);
            lock.unlock();
            assertFalse(waiting.get(3, TimeUnit.SECONDS));
            verifyNoInteractions(remote);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
            worker.shutdownNow();
        }
    }

    @Test
    void tomcatBlockingAndCloseTimeoutsAreFiniteLongValues() {
        WebSocketSender.initialize(session);
        assertEquals(2000L, properties.get("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT"));
        assertEquals(2000L, properties.get("org.apache.tomcat.websocket.SESSION_CLOSE_TIMEOUT"));
    }

    @Test
    void ioFailureClosesAndPoisonsTheOldSessionInsteadOfRetryingItsWriter() throws Exception {
        doThrow(new IOException("slow client timed out")).when(remote).sendText("first");
        assertFalse(WebSocketSender.send(session, "first"));
        assertFalse(WebSocketSender.send(session, "second"));
        verify(remote, times(1)).sendText(anyString());
        verify(session).close(any(jakarta.websocket.CloseReason.class));
    }

    @Test
    void illegalContainerWriteStateIsHandledWithoutEscapingToTheEndpoint() throws Exception {
        doThrow(new IllegalStateException("TEXT_FULL_WRITING")).when(remote).sendText("first");
        assertDoesNotThrow(() -> assertFalse(WebSocketSender.send(session, "first")));
        assertFalse(WebSocketSender.send(session, "second"));
        verify(remote, times(1)).sendText(anyString());
    }
}
