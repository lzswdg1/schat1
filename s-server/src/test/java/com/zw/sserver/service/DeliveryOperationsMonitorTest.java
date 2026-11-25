package com.zw.sserver.service;

import com.zw.sserver.config.DeliveryOpsProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryOperationsMonitorTest {

    @Test
    void alertsOnThresholdChangesThenThrottlesAndReportsRecovery() {
        var properties = new DeliveryOpsProperties();
        var monitor = new DeliveryOperationsMonitor(mock(DeliveryOperationsService.class), properties, mock(DeliveryAlertNotifier.class));
        var healthy = snapshot(0, 0, 0, 0);
        var failed = snapshot(10, 10, 10, 0);
        assertEquals(DeliveryOperationsMonitor.Event.NONE, monitor.evaluate(healthy, 1));
        assertEquals(DeliveryOperationsMonitor.Event.ALERT, monitor.evaluate(failed, 2));
        assertEquals(DeliveryOperationsMonitor.Event.NONE, monitor.evaluate(failed, 3));
        assertEquals(DeliveryOperationsMonitor.Event.ALERT, monitor.evaluate(failed, 2 + properties.getAlertRepeatMs()));
        assertEquals(DeliveryOperationsMonitor.Event.ALERT, monitor.evaluate(snapshot(10, 1000, 300, 10), 1000000));
        assertEquals(DeliveryOperationsMonitor.Event.RECOVERED, monitor.evaluate(healthy, 1000001));
        assertEquals(DeliveryOperationsMonitor.Event.NONE, monitor.evaluate(healthy, 1000002));
        var reconciliationFailure = new DeliveryOperationsService.Snapshot(Map.of(), 0, 0, 0, 0, 1);
        assertEquals(DeliveryOperationsMonitor.Event.ALERT, monitor.evaluate(reconciliationFailure, 1000003));
        assertEquals(DeliveryOperationsMonitor.Event.RECOVERED, monitor.evaluate(healthy, 1000004));
    }

    @Test
    void unavailableSnapshotDoesNotEraseActiveBacklogState() {
        var service = mock(DeliveryOperationsService.class);
        var monitor = new DeliveryOperationsMonitor(service, new DeliveryOpsProperties(), mock(DeliveryAlertNotifier.class));
        var failed = snapshot(10, 10, 0, 0);
        assertEquals(DeliveryOperationsMonitor.Event.ALERT, monitor.evaluate(failed, System.currentTimeMillis()));
        when(service.summary()).thenThrow(new IllegalStateException("database unavailable"));
        assertDoesNotThrow(monitor::monitor);
        assertEquals(DeliveryOperationsMonitor.Event.NONE, monitor.evaluate(failed, System.currentTimeMillis()));
        assertEquals(DeliveryOperationsMonitor.Event.RECOVERED, monitor.evaluate(snapshot(0, 0, 0, 0), System.currentTimeMillis()));
    }

    @Test
    void monitoringCanBeDisabledAndCleanupIsOptIn() {
        var service = mock(DeliveryOperationsService.class);
        var properties = new DeliveryOpsProperties();
        properties.setMonitorEnabled(false);
        var notifier = mock(DeliveryAlertNotifier.class);
        var monitor = new DeliveryOperationsMonitor(service, properties, notifier);
        monitor.monitor();
        monitor.cleanup();
        verifyNoInteractions(service, notifier);
        properties.setEnabled(true);
        properties.setAutoCleanupEnabled(true);
        monitor.cleanup();
        verify(service).cleanup(eq(30), eq(100), eq("scheduled retention"), eq("system:retention"), matches("[a-f0-9-]{36}"));
    }

    @Test
    void publishesBacklogChangesAndRecoveryWithoutRepeatingHealthyOrUnchangedState() {
        var service = mock(DeliveryOperationsService.class);
        var notifier = mock(DeliveryAlertNotifier.class);
        var monitor = new DeliveryOperationsMonitor(service, new DeliveryOpsProperties(), notifier);
        var failed = snapshot(10, 10, 0, 0);
        var healthy = snapshot(0, 0, 0, 0);
        when(service.summary()).thenReturn(failed, failed, healthy, healthy);
        for (int attempt = 0; attempt < 4; attempt++) monitor.monitor();
        var ordered = inOrder(notifier);
        ordered.verify(notifier).publish(DeliveryAlertNotifier.Topic.BACKLOG, DeliveryAlertNotifier.State.FIRING, Set.of("FAILED"), failed);
        ordered.verify(notifier).publish(DeliveryAlertNotifier.Topic.BACKLOG, DeliveryAlertNotifier.State.RESOLVED, Set.of(), healthy);
        ordered.verifyNoMoreInteractions();
    }

    @Test
    void databaseFailureNotifiesAvailabilityWithoutFalselyResolvingActiveBacklog() {
        var service = mock(DeliveryOperationsService.class);
        var notifier = mock(DeliveryAlertNotifier.class);
        var monitor = new DeliveryOperationsMonitor(service, new DeliveryOpsProperties(), notifier);
        var failed = snapshot(10, 10, 0, 0);
        when(service.summary()).thenReturn(failed);
        monitor.monitor();
        when(service.summary()).thenThrow(new IllegalStateException("private database details"));
        monitor.monitor();
        monitor.monitor();
        doReturn(failed).when(service).summary();
        monitor.monitor();
        var ordered = inOrder(notifier);
        ordered.verify(notifier).publish(DeliveryAlertNotifier.Topic.BACKLOG, DeliveryAlertNotifier.State.FIRING, Set.of("FAILED"), failed);
        ordered.verify(notifier).publish(DeliveryAlertNotifier.Topic.MONITOR, DeliveryAlertNotifier.State.FIRING, Set.of("MONITOR_UNAVAILABLE"), null);
        ordered.verify(notifier).publish(DeliveryAlertNotifier.Topic.MONITOR, DeliveryAlertNotifier.State.RESOLVED, Set.of(), null);
        ordered.verifyNoMoreInteractions();
    }

    @Test
    void automaticRetentionFailureAndLaterRecoveryPublishSeparateState() {
        var service = mock(DeliveryOperationsService.class);
        var notifier = mock(DeliveryAlertNotifier.class);
        var properties = new DeliveryOpsProperties();
        properties.setAutoCleanupEnabled(true);
        var monitor = new DeliveryOperationsMonitor(service, properties, notifier);
        when(service.cleanup(anyInt(), anyInt(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("private database details")).thenReturn(0);
        monitor.cleanup();
        monitor.cleanup();
        monitor.cleanup();
        var ordered = inOrder(notifier);
        ordered.verify(notifier).publish(DeliveryAlertNotifier.Topic.RETENTION, DeliveryAlertNotifier.State.FIRING, Set.of("RETENTION_FAILED"), null);
        ordered.verify(notifier).publish(DeliveryAlertNotifier.Topic.RETENTION, DeliveryAlertNotifier.State.RESOLVED, Set.of(), null);
        ordered.verifyNoMoreInteractions();
    }

    private static DeliveryOperationsService.Snapshot snapshot(long failed, long backlog, long age, long cache) {
        return new DeliveryOperationsService.Snapshot(Map.of(), failed, backlog, age, cache, 0);
    }
}
