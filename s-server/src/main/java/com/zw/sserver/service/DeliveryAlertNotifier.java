package com.zw.sserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.config.DeliveryAlertProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class DeliveryAlertNotifier implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DeliveryAlertNotifier.class);
    private final DeliveryAlertProperties properties;
    private final ObjectMapper mapper;
    private final String instanceId;
    private final String processId = UUID.randomUUID().toString();
    private final Map<Topic, Pending> pending = new EnumMap<>(Topic.class);
    private final Object lock = new Object();
    private final HttpClient client;
    private final ScheduledThreadPoolExecutor executor;
    private ScheduledFuture<?> scheduled;
    private boolean sending;
    private boolean closed;
    private long sequence;

    public DeliveryAlertNotifier(DeliveryAlertProperties properties, ObjectMapper mapper,
                                  @Value("${schat.instance.id:localhost}") String instanceId) {
        this.properties = properties;
        this.mapper = mapper;
        this.instanceId = instanceId;
        if (properties.isEnabled()) {
            properties.validate();
            client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            executor = new ScheduledThreadPoolExecutor(1, task -> {
                Thread worker = new Thread(task, "delivery-alert-webhook");
                worker.setDaemon(true);
                return worker;
            });
            executor.setRemoveOnCancelPolicy(true);
        } else {
            client = null;
            executor = null;
        }
    }

    public void publish(Topic topic, State state, Set<String> alerts, DeliveryOperationsService.Snapshot metrics) {
        synchronized (lock) {
            if (closed || executor == null) return;
            String eventId = UUID.randomUUID().toString();
            var event = new AlertEvent(1, eventId, processId, ++sequence, instanceId, topic, state,
                    System.currentTimeMillis(), Set.copyOf(alerts), metrics);
            try {
                pending.put(topic, new Pending(event, mapper.writeValueAsBytes(event)));
                if (!sending) schedule(0);
            } catch (JsonProcessingException exception) {
                log.warn("delivery_alert_dropped eventId={} topic={} reason=SERIALIZATION", eventId, topic);
            }
        }
    }

    private void dispatch() {
        Pending delivery;
        synchronized (lock) {
            scheduled = null;
            if (closed || pending.isEmpty() || sending) return;
            delivery = pending.values().stream().min(Comparator.comparingLong(item -> item.nextAttemptAt)).orElseThrow();
            long delay = delivery.nextAttemptAt - System.nanoTime();
            if (delay > 0) {
                schedule(delay);
                return;
            }
            sending = true;
        }
        String failure = send(delivery);
        synchronized (lock) {
            sending = false;
            if (closed) return;
            if (pending.get(delivery.event.topic()) == delivery) {
                delivery.attempts++;
                if (failure == null) {
                    pending.remove(delivery.event.topic());
                    log.info("delivery_alert_sent eventId={} topic={} state={} attempts={}",
                            delivery.event.eventId(), delivery.event.topic(), delivery.event.state(), delivery.attempts);
                } else if (delivery.attempts >= properties.getMaxAttempts()) {
                    pending.remove(delivery.event.topic());
                    log.warn("delivery_alert_exhausted eventId={} topic={} attempts={} reason={}",
                            delivery.event.eventId(), delivery.event.topic(), delivery.attempts, failure);
                } else {
                    long backoff = Math.min(properties.getRetryMaxMs(), properties.getRetryInitialMs() * (1L << (delivery.attempts - 1)));
                    delivery.nextAttemptAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoff);
                    log.warn("delivery_alert_retry eventId={} topic={} attempts={} reason={}",
                            delivery.event.eventId(), delivery.event.topic(), delivery.attempts, failure);
                }
            }
            if (!pending.isEmpty()) {
                long next = pending.values().stream().mapToLong(item -> item.nextAttemptAt).min().orElseThrow();
                schedule(Math.max(0, next - System.nanoTime()));
            }
        }
    }

    private String send(Pending delivery) {
        try {
            String timestamp = Long.toString(System.currentTimeMillis());
            Mac signature = Mac.getInstance("HmacSHA256");
            signature.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            signature.update((timestamp + "\n").getBytes(StandardCharsets.UTF_8));
            String digest = HexFormat.of().formatHex(signature.doFinal(delivery.body));
            var request = HttpRequest.newBuilder(URI.create(properties.getUrl()))
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("X-SChat-Alert-Id", delivery.event.eventId())
                    .header("X-SChat-Alert-Timestamp", timestamp)
                    .header("X-SChat-Alert-Signature", "sha256=" + digest)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(delivery.body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var ignored = response.body()) {
                return response.statusCode() >= 200 && response.statusCode() < 300 ? null : "HTTP_" + response.statusCode();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "INTERRUPTED";
        } catch (Exception exception) {
            return exception.getClass().getSimpleName();
        }
    }

    private void schedule(long delayNanos) {
        if (scheduled != null) scheduled.cancel(false);
        scheduled = executor.schedule(this::dispatch, delayNanos, TimeUnit.NANOSECONDS);
    }

    boolean isIdle() {
        synchronized (lock) {
            return pending.isEmpty() && !sending;
        }
    }

    int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    @PreDestroy
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            pending.clear();
            if (scheduled != null) scheduled.cancel(false);
            if (executor != null) executor.shutdownNow();
        }
    }

    public enum Topic { BACKLOG, MONITOR, RETENTION }
    public enum State { FIRING, RESOLVED }
    public record AlertEvent(int schemaVersion, String eventId, String processId, long sequence, String instanceId,
                             Topic topic, State state, long observedAtMillis, Set<String> alerts,
                             DeliveryOperationsService.Snapshot metrics) { }

    private static final class Pending {
        private final AlertEvent event;
        private final byte[] body;
        private int attempts;
        private long nextAttemptAt = System.nanoTime();

        private Pending(AlertEvent event, byte[] body) {
            this.event = event;
            this.body = body;
        }
    }
}
