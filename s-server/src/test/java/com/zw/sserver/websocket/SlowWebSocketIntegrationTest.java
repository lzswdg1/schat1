package com.zw.sserver.websocket;

import com.zw.sserver.infrastructure.message.DeliveryEnvelope;
import com.zw.sserver.websocket.impl.DefaultLocalSessionManager;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SCHAT_TEST_SLOW_WEBSOCKET", matches = "true")
class SlowWebSocketIntegrationTest {

    private static final LinkedBlockingQueue<Session> opened = new LinkedBlockingQueue<>();
    private static final String LARGE_FRAME = "x".repeat(16 * 1024 * 1024);
    private Tomcat tomcat;
    private int port;
    @TempDir
    Path directory;

    @ServerEndpoint("/transport")
    public static class TransportEndpoint {
        @OnOpen
        public void opened(Session session) {
            opened.add(session);
        }
    }

    @BeforeEach
    void start() throws Exception {
        opened.clear();
        tomcat = new Tomcat();
        tomcat.setBaseDir(directory.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        var context = tomcat.addContext("", directory.toString());
        context.setParentClassLoader(getClass().getClassLoader());
        Tomcat.addServlet(context, "default", new org.apache.catalina.servlets.DefaultServlet());
        context.addServletMappingDecoded("/", "default");
        context.addServletContainerInitializer(new WsSci(), Set.of(TransportEndpoint.class));
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterEach
    void stop() throws Exception {
        try {
            if (tomcat != null) tomcat.stop();
        } finally {
            if (tomcat != null) tomcat.destroy();
            opened.clear();
        }
    }

    private Peer connect() throws Exception {
        Socket socket = new Socket();
        try {
            socket.setReceiveBufferSize(1024);
            socket.setSoTimeout(4000);
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            String request = "GET /transport HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13"
                    + "\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            StringBuilder response = new StringBuilder();
            while (!response.toString().endsWith("\r\n\r\n") && response.length() < 8192) {
                int value = socket.getInputStream().read();
                assertTrue(value >= 0, "Handshake closed unexpectedly");
                response.append((char) value);
            }
            assertTrue(response.toString().startsWith("HTTP/1.1 101"), response.toString());
            Session session = opened.poll(3, TimeUnit.SECONDS);
            assertNotNull(session);
            return new Peer(socket, session);
        } catch (Throwable failure) {
            socket.close();
            throw failure;
        }
    }

    private String readSmallText(Peer peer) throws Exception {
        var input = new DataInputStream(peer.socket().getInputStream());
        assertEquals(0x81, input.readUnsignedByte());
        int length = input.readUnsignedByte();
        assertTrue(length < 126, "Expected a small unmasked server text frame");
        byte[] bytes = input.readNBytes(length);
        assertEquals(length, bytes.length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void aNonReadingPeerTimesOutWithoutBlockingAnotherActualSession() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (Peer slow = connect(); Peer healthy = connect()) {
            var started = new CountDownLatch(1);
            long began = System.nanoTime();
            var writing = worker.submit(() -> {
                started.countDown();
                return WebSocketSender.send(slow.session(), LARGE_FRAME);
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertFalse(writing.isDone(), "The real peer must actually apply TCP backpressure");
            assertTrue(WebSocketSender.send(healthy.session(), "healthy"));
            assertEquals("healthy", readSmallText(healthy));
            assertFalse(writing.get(10, TimeUnit.SECONDS));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            assertTrue(elapsed >= 1500 && elapsed < 8000, "Expected the configured write deadline, elapsed=" + elapsed);
            assertFalse(WebSocketSender.send(slow.session(), "must-not-reuse-failed-writer"));
            System.out.printf("SLOW_WEBSOCKET write_timeout_ms=%d receive_buffer=%d healthy_peer=true%n",
                    elapsed, slow.socket().getReceiveBufferSize());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void replacingARealBlockedConnectionPreservesTheNewConnectionsWriter() throws Exception {
        var envelopes = mock(DeliveryEnvelope.class);
        when(envelopes.maySend(anyLong(), anyString())).thenReturn(true);
        var sessions = new DefaultLocalSessionManager(envelopes);
        var worker = Executors.newSingleThreadExecutor();
        try (Peer slow = connect(); Peer replacement = connect()) {
            sessions.addSession(1L, slow.session());
            var started = new CountDownLatch(1);
            var writing = worker.submit(() -> {
                started.countDown();
                return sessions.sendToUser(1L, LARGE_FRAME);
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertFalse(writing.isDone());
            sessions.addSession(1L, replacement.session());
            sessions.removeSession(1L, slow.session());
            assertTrue(sessions.sendToUser(1L, "after-reconnect"));
            assertEquals("after-reconnect", readSmallText(replacement));
            assertFalse(writing.get(10, TimeUnit.SECONDS));
            sessions.removeSession(1L, slow.session());
            assertSame(replacement.session(), sessions.getSession(1L));
            assertTrue(sessions.sendToUser(1L, "still-current"));
            assertEquals("still-current", readSmallText(replacement));
            System.out.println("SLOW_WEBSOCKET real_reconnect_writer_preserved=true");
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private record Peer(Socket socket, Session session) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            socket.close();
            if (session.isOpen()) session.close();
        }
    }
}
