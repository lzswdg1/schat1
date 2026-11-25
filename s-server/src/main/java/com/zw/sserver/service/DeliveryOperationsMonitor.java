package com.zw.sserver.service;

import com.zw.sserver.config.DeliveryOpsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Component
public class DeliveryOperationsMonitor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOperationsMonitor.class);
    private final DeliveryOperationsService service;
    private final DeliveryOpsProperties properties;
    private final DeliveryAlertNotifier notifier;
    private Set<String> activeAlerts = Set.of();
    private long lastAlertAt;
    private boolean unavailable;
    private long lastUnavailableAlertAt;
    private boolean retentionFailed;

    public DeliveryOperationsMonitor(DeliveryOperationsService service, DeliveryOpsProperties properties,
                                     DeliveryAlertNotifier notifier) {
        this.service = service;
        this.properties = properties;
        this.notifier = notifier;
    }

    @Scheduled(fixedDelayString = "${schat.delivery-ops.monitor-interval-ms:60000}")
    public void monitor() {
        if (!properties.isMonitorEnabled()) {
            return;
        }
        DeliveryOperationsService.Snapshot snapshot;
        long now = System.currentTimeMillis();
        try {
            snapshot = service.summary();
        } catch (RuntimeException exception) {
            if (!unavailable || now - lastUnavailableAlertAt >= properties.getAlertRepeatMs()) {
                log.warn("delivery_ops_monitor_unavailable errorType={}", exception.getClass().getSimpleName());
                notifier.publish(DeliveryAlertNotifier.Topic.MONITOR, DeliveryAlertNotifier.State.FIRING,
                        Set.of("MONITOR_UNAVAILABLE"), null);
                lastUnavailableAlertAt = now;
            }
            unavailable = true;
            return;
        }
        if (unavailable) {
            log.info("delivery_ops_monitor_available");
            unavailable = false;
            notifier.publish(DeliveryAlertNotifier.Topic.MONITOR, DeliveryAlertNotifier.State.RESOLVED, Set.of(), null);
        }
        var event = evaluate(snapshot, now);
        if (event == Event.ALERT) {
            log.warn("delivery_ops_backlog alerts={} failed={} unconfirmed={} oldestSeconds={} cachePending={} cacheErrors={}",
                    activeAlerts, snapshot.failedCount(), snapshot.unconfirmedCount(), snapshot.oldestUnconfirmedSeconds(),
                    snapshot.pendingCacheInvalidations(), snapshot.cacheInvalidationErrors());
            notifier.publish(DeliveryAlertNotifier.Topic.BACKLOG, DeliveryAlertNotifier.State.FIRING, activeAlerts, snapshot);
        } else if (event == Event.RECOVERED) {
            log.info("delivery_ops_backlog_recovered");
            notifier.publish(DeliveryAlertNotifier.Topic.BACKLOG, DeliveryAlertNotifier.State.RESOLVED, Set.of(), snapshot);
        }
    }

    @Scheduled(fixedDelayString = "${schat.delivery-ops.cleanup-interval-ms:3600000}",
            initialDelayString = "${schat.delivery-ops.cleanup-interval-ms:3600000}")
    public void cleanup() {
        if (!properties.isAutoCleanupEnabled()) {
            return;
        }
        String requestId = UUID.randomUUID().toString();
        try {
            int removed = service.cleanup(properties.getRetentionDays(), properties.getCleanupBatchSize(),
                    "scheduled retention", "system:retention", requestId);
            if (removed > 0) {
                log.info("delivery_ops_retention requestId={} removed={}", requestId, removed);
            }
            if (retentionFailed) {
                retentionFailed = false;
                notifier.publish(DeliveryAlertNotifier.Topic.RETENTION, DeliveryAlertNotifier.State.RESOLVED, Set.of(), null);
            }
        } catch (RuntimeException exception) {
            log.warn("delivery_ops_retention_failed requestId={} errorType={}", requestId, exception.getClass().getSimpleName());
            retentionFailed = true;
            notifier.publish(DeliveryAlertNotifier.Topic.RETENTION, DeliveryAlertNotifier.State.FIRING,
                    Set.of("RETENTION_FAILED"), null);
        }
    }

    Event evaluate(DeliveryOperationsService.Snapshot snapshot, long now) {
        Set<String> alerts = new LinkedHashSet<>();
        if (snapshot.failedCount() >= properties.getFailedThreshold()) alerts.add("FAILED");
        if (snapshot.unconfirmedCount() >= properties.getBacklogThreshold()) alerts.add("BACKLOG");
        if (snapshot.oldestUnconfirmedSeconds() >= properties.getOldestTaskSecondsThreshold()) alerts.add("OLDEST_TASK");
        if (snapshot.pendingCacheInvalidations() >= properties.getCachePendingThreshold()) alerts.add("CACHE_INVALIDATION");
        if (snapshot.cacheInvalidationErrors() >= properties.getCacheErrorThreshold()) alerts.add("CACHE_ERROR");
        boolean changed = !alerts.equals(activeAlerts);
        activeAlerts = Set.copyOf(alerts);
        if (!alerts.isEmpty() && (changed || now - lastAlertAt >= properties.getAlertRepeatMs())) {
            lastAlertAt = now;
            return Event.ALERT;
        }
        return changed ? Event.RECOVERED : Event.NONE;
    }

    enum Event { NONE, ALERT, RECOVERED }
}
