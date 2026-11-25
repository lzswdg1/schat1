package com.zw.sserver.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.zw.sserver.config.DeliveryAlertProperties;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.zw.sserver.service.DeliveryAlertNotifier.State.*;
import static com.zw.sserver.service.DeliveryAlertNotifier.Topic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class DeliveryAlertNotifierTest {

    private static final String SECRET = "independent-webhook-test-secret-0123456789";
    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<Received> requests = new LinkedBlockingQueue<>();
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>(exchange -> respond(exchange, 200));
    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<CountDownLatch> gates = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private HttpServer server;
    private ExecutorService receiver;
    private DeliveryAlertProperties properties;
    private DeliveryAlertNotifier notifier;

    @BeforeEach
    void startReceiver() throws IOException {
        logs.list = new CopyOnWriteArrayList<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(DeliveryAlertNotifier.class)).addAppender(logs);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver = Executors.newCachedThreadPool(task -> {
            var worker = new Thread(task, "test-webhook-receiver");
            worker.setDaemon(true);
            return worker;
        });
        server.setExecutor(receiver);
        server.createContext("/", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                requestCount.incrementAndGet();
                requests.add(new Received(body, exchange.getRequestHeaders().getFirst("X-SChat-Alert-Id"),
                        exchange.getRequestHeaders().getFirst("X-SChat-Alert-Timestamp"),
                        exchange.getRequestHeaders().getFirst("X-SChat-Alert-Signature")));
                handler.get().handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        properties = new DeliveryAlertProperties();
        properties.setEnabled(true);
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/private-url-token");
        properties.setSecret(SECRET);
        properties.setAllowLoopbackHttp(true);
        properties.setConnectTimeoutMs(100);
        properties.setRequestTimeoutMs(500);
        properties.setMaxAttempts(3);
        properties.setRetryInitialMs(100);
        properties.setRetryMaxMs(200);
    }

    @AfterEach
    void stopReceiver() throws InterruptedException {
        for (var gate : gates) gate.countDown();
        if (notifier != null) notifier.close();
        if (server != null) server.stop(0);
        if (receiver != null) {
            receiver.shutdownNow();
            assertTrue(receiver.awaitTermination(3, TimeUnit.SECONDS));
        }
        ((Logger) LoggerFactory.getLogger(DeliveryAlertNotifier.class)).detachAppender(logs);
        logs.stop();
    }

    @Test
    void sendsSignedMetadataOnlyAndNeverLogsEndpointOrCredentials() throws Exception {
        startNotifier();
        var metrics = new DeliveryOperationsService.Snapshot(Map.of("FAILED", 10L), 10, 20, 300, 0, 0);
        notifier.publish(BACKLOG, FIRING, Set.of("FAILED", "OLDEST_TASK"), metrics);
        var received = take();
        var body = mapper.readTree(received.body());
        assertEquals(1, body.path("schemaVersion").asInt());
        assertEquals("test-node:1", body.path("instanceId").asText());
        assertEquals("BACKLOG", body.path("topic").asText());
        assertEquals("FIRING", body.path("state").asText());
        assertEquals(10, body.path("metrics").path("failedCount").asLong());
        assertEquals(received.eventId(), body.path("eventId").asText());
        assertDoesNotThrow(() -> java.util.UUID.fromString(received.eventId()));
        assertTrue(Math.abs(System.currentTimeMillis() - Long.parseLong(received.timestamp())) < 5000);
        var signature = Mac.getInstance("HmacSHA256");
        signature.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        signature.update((received.timestamp() + "\n").getBytes(StandardCharsets.UTF_8));
        assertEquals("sha256=" + HexFormat.of().formatHex(signature.doFinal(received.body())), received.signature());
        assertFalse(new String(received.body(), StandardCharsets.UTF_8).contains(SECRET));
        assertFalse(body.has("payload"));
        await(notifier::isIdle);
        assertPrivateLogs();
    }

    @Test
    void disabledNotifierSendsNothing() throws Exception {
        properties.setEnabled(false);
        startNotifier();
        notifier.publish(BACKLOG, FIRING, Set.of("FAILED"), null);
        assertTrue(notifier.isIdle());
        assertNull(requests.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void retriesWithStableIdAndBodyUntilTheReceiverAcceptsIt() throws Exception {
        handler.set(exchange -> respond(exchange, requestCount.get() == 1 ? 503 : 204));
        startNotifier();
        notifier.publish(MONITOR, FIRING, Set.of("MONITOR_UNAVAILABLE"), null);
        var first = take();
        var second = take();
        assertEquals(first.eventId(), second.eventId());
        assertArrayEquals(first.body(), second.body());
        await(notifier::isIdle);
        assertEquals(2, requestCount.get());
    }

    @Test
    void exhaustsTheConfiguredAttemptBudgetWithoutLoggingResponseBodies() throws Exception {
        handler.set(exchange -> {
            byte[] body = "never-log-private-response-sentinel".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
        });
        startNotifier();
        notifier.publish(RETENTION, FIRING, Set.of("RETENTION_FAILED"), null);
        take();
        take();
        take();
        await(notifier::isIdle);
        assertNull(requests.poll(300, TimeUnit.MILLISECONDS));
        assertEquals(3, requestCount.get());
        assertTrue(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains("delivery_alert_exhausted")));
        assertPrivateLogs();
    }

    @Test
    void slowReceiverDoesNotBlockPublishAndOnlyLatestPendingStateSurvivesPerTopic() throws Exception {
        var release = gate();
        properties.setRequestTimeoutMs(3000);
        handler.set(exchange -> {
            if (requestCount.get() == 1) waitFor(release);
            respond(exchange, 200);
        });
        startNotifier();
        notifier.publish(BACKLOG, FIRING, Set.of("FAILED"), null);
        var first = take();
        long started = System.nanoTime();
        for (int count = 0; count < 100; count++) notifier.publish(BACKLOG, FIRING, Set.of("FAILED"), null);
        notifier.publish(BACKLOG, RESOLVED, Set.of(), null);
        notifier.publish(MONITOR, FIRING, Set.of("MONITOR_UNAVAILABLE"), null);
        notifier.publish(RETENTION, FIRING, Set.of("RETENTION_FAILED"), null);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500);
        assertEquals(3, notifier.pendingCount());
        release.countDown();
        var next = take();
        var body = mapper.readTree(next.body());
        assertNotEquals(first.eventId(), next.eventId());
        assertEquals("BACKLOG", body.path("topic").asText());
        assertEquals("RESOLVED", body.path("state").asText());
        assertTrue(body.path("sequence").asLong() > mapper.readTree(first.body()).path("sequence").asLong());
        take();
        take();
        await(notifier::isIdle);
        assertEquals(4, requestCount.get());
    }

    @Test
    void newRecoveryWakesAWaitingRetryAndDoesNotResendTheObsoleteAlarm() throws Exception {
        properties.setRetryInitialMs(1000);
        properties.setRetryMaxMs(1000);
        handler.set(exchange -> respond(exchange, requestCount.get() == 1 ? 503 : 200));
        startNotifier();
        notifier.publish(BACKLOG, FIRING, Set.of("FAILED"), null);
        take();
        await(() -> hasLog("delivery_alert_retry"));
        notifier.publish(BACKLOG, RESOLVED, Set.of(), null);
        var recovered = requests.poll(700, TimeUnit.MILLISECONDS);
        assertNotNull(recovered);
        assertEquals("RESOLVED", mapper.readTree(recovered.body()).path("state").asText());
        await(notifier::isIdle);
        assertNull(requests.poll(1100, TimeUnit.MILLISECONDS));
    }

    @Test
    void failureInOneTopicDoesNotDelayAnotherTopicUntilItsBackoffExpires() throws Exception {
        properties.setRetryInitialMs(1000);
        properties.setRetryMaxMs(1000);
        handler.set(exchange -> respond(exchange, requestCount.get() == 1 ? 503 : 200));
        startNotifier();
        notifier.publish(BACKLOG, FIRING, Set.of("FAILED"), null);
        take();
        await(() -> hasLog("delivery_alert_retry"));
        notifier.publish(MONITOR, FIRING, Set.of("MONITOR_UNAVAILABLE"), null);
        var available = requests.poll(700, TimeUnit.MILLISECONDS);
        assertNotNull(available);
        assertEquals("MONITOR", mapper.readTree(available.body()).path("topic").asText());
        assertEquals("BACKLOG", mapper.readTree(take().body()).path("topic").asText());
        await(notifier::isIdle);
    }

    @Test
    void redirectsAreNeverFollowedAndSigningHeadersStayOnTheConfiguredEndpoint() throws Exception {
        properties.setMaxAttempts(1);
        handler.set(exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/redirected");
            respond(exchange, 302);
        });
        startNotifier();
        notifier.publish(MONITOR, FIRING, Set.of("MONITOR_UNAVAILABLE"), null);
        take();
        await(notifier::isIdle);
        assertEquals(1, requestCount.get());
        assertTrue(hasLog("HTTP_302"));
    }

    @Test
    void slowResponseHeadersTimeOutAndCloseStopsAllPendingRetries() throws Exception {
        var release = gate();
        properties.setRequestTimeoutMs(200);
        properties.setRetryInitialMs(1000);
        properties.setRetryMaxMs(1000);
        handler.set(exchange -> waitFor(release));
        startNotifier();
        notifier.publish(BACKLOG, FIRING, Set.of("FAILED"), null);
        take();
        await(() -> hasLog("HttpTimeoutException"));
        notifier.close();
        release.countDown();
        notifier.publish(MONITOR, FIRING, Set.of("MONITOR_UNAVAILABLE"), null);
        assertEquals(0, notifier.pendingCount());
        assertNull(requests.poll(1100, TimeUnit.MILLISECONDS));
        notifier.close();
    }

    @Test
    void acceptingStatusDoesNotWaitForOrBufferAnUnboundedResponseBody() throws Exception {
        var release = gate();
        handler.set(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            waitFor(release);
        });
        startNotifier();
        notifier.publish(BACKLOG, RESOLVED, Set.of(), null);
        take();
        await(notifier::isIdle);
        assertEquals(1, requestCount.get());
        release.countDown();
    }

    private void startNotifier() {
        notifier = new DeliveryAlertNotifier(properties, mapper, "test-node:1");
    }

    private CountDownLatch gate() {
        var release = new CountDownLatch(1);
        gates.add(release);
        return release;
    }

    private Received take() throws InterruptedException {
        var received = requests.poll(3, TimeUnit.SECONDS);
        assertNotNull(received, "webhook request must arrive within three seconds");
        return received;
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private static void waitFor(CountDownLatch gate) throws IOException {
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) throw new IOException("test receiver timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("test receiver interrupted");
        }
    }

    private static void await(BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(ready.getAsBoolean(), "condition must complete within five seconds");
    }

    private boolean hasLog(String value) {
        return logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains(value));
    }

    private void assertPrivateLogs() {
        for (var event : logs.list) {
            assertFalse(event.getFormattedMessage().contains(SECRET));
            assertFalse(event.getFormattedMessage().contains("private-url-token"));
            assertFalse(event.getFormattedMessage().contains("never-log-private-response-sentinel"));
        }
    }

    private record Received(byte[] body, String eventId, String timestamp, String signature) { }
}
