package com.zw.sserver.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryAlertConfigurationTest {

    private static final String SECRET = "independent-alert-test-secret-0123456789";

    @Test
    void webhookIsDisabledWithoutNeedingAnEndpointOrSecret() {
        var properties = new DeliveryAlertProperties();
        assertFalse(properties.isEnabled());
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void productionEndpointsRequireHttpsAndNeverAcceptEmbeddedCredentialsOrFragments() {
        var properties = valid();
        for (String endpoint : List.of("", "not a URI", "http://example.test/hook", "ftp://example.test/hook",
                "https://user:password@example.test/hook", "https://example.test/hook#token", "https://example.test:0/hook",
                "https://example.test:65536/hook")) {
            properties.setUrl(endpoint);
            var exception = assertThrows(IllegalStateException.class, properties::validate);
            assertFalse(exception.getMessage().contains(endpoint.length() > 0 ? endpoint : "password"));
            assertNull(exception.getCause());
        }
        properties.setUrl("https://alerts.example.test/webhook?tenant=schat");
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void plaintextHttpRequiresExplicitOptInAndLiteralLoopbackAddresses() {
        var properties = valid();
        properties.setUrl("http://127.0.0.1:38888/hook");
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setAllowLoopbackHttp(true);
        assertDoesNotThrow(properties::validate);
        properties.setUrl("http://[::1]:38888/hook");
        assertDoesNotThrow(properties::validate);
        for (String host : List.of("localhost", "127.0.0.2", "example.test", "127.0.0.1.example.test", "10.0.0.1")) {
            properties.setUrl("http://" + host + "/hook");
            assertThrows(IllegalStateException.class, properties::validate);
        }
    }

    @Test
    void signingKeyCannotReuseAnyApplicationKey() {
        var properties = valid();
        var operations = new DeliveryOpsProperties();
        assertThrows(IllegalStateException.class, () -> new DeliveryAlertConfiguration(properties, operations, SECRET, "jwt"));
        assertThrows(IllegalStateException.class, () -> new DeliveryAlertConfiguration(properties, operations, "internal", SECRET));
        operations.setSecret(SECRET);
        assertThrows(IllegalStateException.class, () -> new DeliveryAlertConfiguration(properties, operations, "internal", "jwt"));
        operations.setSecret("different-operations-key");
        assertDoesNotThrow(() -> new DeliveryAlertConfiguration(properties, operations, "internal", "jwt"));
        properties.setSecret("short");
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void invalidTimeoutsAndUnboundedRetrySettingsAreRejected() {
        var properties = valid();
        properties.setConnectTimeoutMs(99);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setConnectTimeoutMs(1000);
        properties.setRequestTimeoutMs(999);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setRequestTimeoutMs(15001);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setRequestTimeoutMs(3000);
        properties.setMaxAttempts(11);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setMaxAttempts(3);
        properties.setRetryInitialMs(0);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setRetryInitialMs(1000);
        properties.setRetryMaxMs(999);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setRetryMaxMs(300001);
        assertThrows(IllegalStateException.class, properties::validate);
    }

    private static DeliveryAlertProperties valid() {
        var properties = new DeliveryAlertProperties();
        properties.setEnabled(true);
        properties.setSecret(SECRET);
        properties.setUrl("https://alerts.example.test/webhook");
        return properties;
    }
}
