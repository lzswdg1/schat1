package com.zw.sserver.websocket.impl;

import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DefaultLocalSessionManager 单元测试。
 * 覆盖 addSession/removeSession 生命周期、sendToUser 异常处理、
 * closeAll 行为，以及补丁 5 的并发安全性。
 */
@ExtendWith(MockitoExtension.class)
class DefaultLocalSessionManagerTest {

    private DefaultLocalSessionManager manager;

    @Mock
    private Session session;

    @Mock
    private Session session2;

    @Mock
    private RemoteEndpoint.Basic basicRemote;

    @Mock
    private RemoteEndpoint.Basic basicRemote2;

    @BeforeEach
    void setUp() {
        lenient().when(session.getUserProperties()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
        lenient().when(session2.getUserProperties()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
        var envelopes = mock(com.zw.sserver.infrastructure.message.DeliveryEnvelope.class);
        lenient().when(envelopes.maySend(anyLong(), anyString())).thenReturn(true);
        manager = new DefaultLocalSessionManager(envelopes);
    }

    // ========== addSession / removeSession lifecycle ==========

    @Test
    @DisplayName("addSession 后 getSession 返回对应 Session")
    void addSession_thenGetSession_returnsSession() {
        manager.addSession(1L, session);
        assertSame(session, manager.getSession(1L));
    }

    @Test
    @DisplayName("removeSession 后 getSession 返回 null")
    void removeSession_thenGetSession_returnsNull() {
        manager.addSession(1L, session);
        manager.removeSession(1L, session);
        assertNull(manager.getSession(1L));
    }

    @Test
    @DisplayName("removeSession 仅当 session 匹配时才移除")
    void removeSession_onlyRemovesMatchingSession() {
        manager.addSession(1L, session);
        manager.removeSession(1L, session2); // different session — should NOT remove
        assertSame(session, manager.getSession(1L));
    }

    @Test
    @DisplayName("addSession 覆盖同一用户的旧 Session")
    void addSession_overwritesPreviousSession() {
        manager.addSession(1L, session);
        manager.addSession(1L, session2);
        assertSame(session2, manager.getSession(1L));
    }

    @Test
    @DisplayName("getAllLocalUsers 返回所有已注册用户 ID")
    void getAllLocalUsers_returnsAllRegisteredUserIds() {
        manager.addSession(1L, session);
        manager.addSession(2L, session2);
        Set<Long> users = manager.getAllLocalUsers();
        assertEquals(2, users.size());
        assertTrue(users.contains(1L));
        assertTrue(users.contains(2L));
    }

    // ========== sendToUser success and IOException handling ==========

    @Test
    @DisplayName("sendToUser 成功发送消息返回 true")
    void sendToUser_success_returnsTrue() throws IOException {
        when(session.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(basicRemote);

        manager.addSession(1L, session);
        assertTrue(manager.sendToUser(1L, "hello"));

        verify(basicRemote).sendText("hello");
    }

    @Test
    @DisplayName("sendToUser 用户不存在返回 false")
    void sendToUser_userNotFound_returnsFalse() {
        assertFalse(manager.sendToUser(999L, "hello"));
    }

    @Test
    @DisplayName("sendToUser Session 已关闭返回 false")
    void sendToUser_sessionClosed_returnsFalse() {
        when(session.isOpen()).thenReturn(false);
        manager.addSession(1L, session);
        assertFalse(manager.sendToUser(1L, "hello"));
    }

    @Test
    @DisplayName("sendToUser 发生 IOException 返回 false 且不抛异常")
    void sendToUser_ioException_returnsFalse() throws IOException {
        when(session.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(basicRemote);
        doThrow(new IOException("connection reset")).when(basicRemote).sendText(anyString());

        manager.addSession(1L, session);
        assertFalse(manager.sendToUser(1L, "hello"));
    }

    // ========== closeAll ==========

    @Test
    @DisplayName("closeAll 向所有打开的 Session 发送 CloseReason 并关闭")
    void closeAll_sendsCloseReasonToAllSessions() throws IOException {
        when(session.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);

        manager.addSession(1L, session);
        manager.addSession(2L, session2);

        manager.closeAll("server shutting down");

        ArgumentCaptor<CloseReason> captor1 = ArgumentCaptor.forClass(CloseReason.class);
        ArgumentCaptor<CloseReason> captor2 = ArgumentCaptor.forClass(CloseReason.class);

        verify(session).close(captor1.capture());
        verify(session2).close(captor2.capture());

        assertEquals(CloseReason.CloseCodes.SERVICE_RESTART, captor1.getValue().getCloseCode());
        assertEquals("server shutting down", captor1.getValue().getReasonPhrase());
        assertEquals(CloseReason.CloseCodes.SERVICE_RESTART, captor2.getValue().getCloseCode());
        assertEquals("server shutting down", captor2.getValue().getReasonPhrase());
    }

    @Test
    @DisplayName("closeAll 跳过已关闭的 Session")
    void closeAll_skipsClosedSessions() throws IOException {
        when(session.isOpen()).thenReturn(false);
        manager.addSession(1L, session);

        manager.closeAll("shutdown");

        verify(session, never()).close(any(CloseReason.class));
    }

    @Test
    @DisplayName("closeAll 单个 Session 关闭失败不影响其他 Session")
    void closeAll_ioExceptionOnOneSession_doesNotAffectOthers() throws IOException {
        when(session.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);
        doThrow(new IOException("fail")).when(session).close(any(CloseReason.class));

        manager.addSession(1L, session);
        manager.addSession(2L, session2);

        assertDoesNotThrow(() -> manager.closeAll("shutdown"));
        verify(session2).close(any(CloseReason.class));
    }

    // ========== 补丁 5: 并发 sendToUser 线程安全 ==========

    @Test
    @DisplayName("补丁5: 多线程并发 sendToUser 同一用户不抛出 IllegalStateException")
    void concurrentSendToUser_doesNotThrowIllegalStateException() throws Exception {
        when(session.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(basicRemote);

        manager.addSession(1L, session);

        int threadCount = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Throwable> exceptions = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // all threads start at the same time
                    boolean result = manager.sendToUser(1L, "msg-" + idx);
                    if (result) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    synchronized (exceptions) {
                        exceptions.add(t);
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all threads
        doneLatch.await();
        executor.shutdown();

        // No IllegalStateException or any other exception should have been thrown
        assertTrue(exceptions.isEmpty(),
                "Expected no exceptions but got: " + exceptions);
        // All sends should succeed since session is open and mock doesn't throw
        assertEquals(threadCount, successCount.get());
    }

    // ========== per-session lock lifecycle ==========

    @Test
    @DisplayName("补丁5: addSession 创建 per-session lock，removeSession 移除 lock")
    void perSessionLock_createdOnAdd_removedOnRemove() throws IOException {
        when(session.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(basicRemote);

        // Before add — sendToUser should return false (no session, no lock)
        assertFalse(manager.sendToUser(1L, "test"));

        // After add — sendToUser should work (lock exists)
        manager.addSession(1L, session);
        assertTrue(manager.sendToUser(1L, "test"));

        // After remove — sendToUser should return false (session and lock removed)
        manager.removeSession(1L, session);
        assertFalse(manager.sendToUser(1L, "test"));
    }

    @Test
    @DisplayName("补丁5: removeSession 不匹配时 lock 不被移除")
    void perSessionLock_notRemovedWhenSessionMismatch() throws IOException {
        when(session.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(basicRemote);

        manager.addSession(1L, session);
        manager.removeSession(1L, session2); // mismatch — should keep lock

        // Lock should still exist, sendToUser should still work
        assertTrue(manager.sendToUser(1L, "test"));
    }

    @Test
    void selectedOldSessionCannotReceiveAfterAConcurrentReplacement() throws Exception {
        var replaced = new java.util.concurrent.atomic.AtomicBoolean();
        when(session.isOpen()).thenAnswer(invocation -> {
            if (replaced.compareAndSet(false, true)) manager.addSession(1L, session2);
            return true;
        });
        when(session2.isOpen()).thenReturn(true);
        when(session2.getBasicRemote()).thenReturn(basicRemote2);
        manager.addSession(1L, session);

        assertFalse(manager.sendToUser(1L, "stale"));
        manager.removeSession(1L, session);
        assertSame(session2, manager.getSession(1L));
        assertTrue(manager.sendToUser(1L, "new"));
        verify(basicRemote2).sendText("new");
    }

    @Test
    void replacementRetainsASendableSessionAfterConcurrentOldCleanup() throws Exception {
        when(session2.isOpen()).thenReturn(true);
        when(session2.getBasicRemote()).thenReturn(basicRemote2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 500; attempt++) {
                manager.addSession(1L, session);
                var start = new CountDownLatch(1);
                var remove = workers.submit(() -> { start.await(); manager.removeSession(1L, session); return true; });
                var replace = workers.submit(() -> { start.await(); manager.addSession(1L, session2); return true; });
                start.countDown();
                assertTrue(remove.get(3, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(replace.get(3, java.util.concurrent.TimeUnit.SECONDS));
                assertSame(session2, manager.getSession(1L));
                assertTrue(manager.sendToUser(1L, "new"));
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void aBlockedOldWriterDoesNotBlockTheNewConnectionsWriter() throws Exception {
        when(session.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(basicRemote);
        when(session2.getBasicRemote()).thenReturn(basicRemote2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return null;
        }).when(basicRemote).sendText("old");
        manager.addSession(1L, session);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var old = worker.submit(() -> manager.sendToUser(1L, "old"));
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            manager.addSession(1L, session2);
            manager.removeSession(1L, session);
            assertTrue(manager.sendToUser(1L, "new"));
            release.countDown();
            assertTrue(old.get(3, java.util.concurrent.TimeUnit.SECONDS));
            verify(basicRemote2).sendText("new");
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }
}
