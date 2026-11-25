package com.zw.suser.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class LoginClientIpResolverTest {

    private MockHttpServletRequest request(String forwarded) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        return request;
    }

    @Test
    void defaultDoesNotTrustAnyProxy() {
        assertEquals("10.0.0.1", new LoginClientIpResolver("").resolve(request("198.51.100.1")));
    }

    @Test
    void trustedProxyChainStopsAtTheFirstUntrustedHopFromTheRight() {
        var resolver = new LoginClientIpResolver("10.0.0.1,10.0.0.2");
        assertEquals("192.0.2.10", resolver.resolve(request("198.51.100.1,192.0.2.10,10.0.0.2")));
    }

    @Test
    void aForgedLeftmostHopCannotReplaceTheClientAddress() {
        var resolver = new LoginClientIpResolver("10.0.0.1");
        assertEquals("192.0.2.10", resolver.resolve(request("198.51.100.1,192.0.2.10")));
        assertEquals("192.0.2.10", resolver.resolve(request("203.0.113.9,192.0.2.10")));
    }

    @Test
    void malformedOrExcessiveForwardingDataFallsBackToThePeer() {
        var resolver = new LoginClientIpResolver("10.0.0.1");
        for (String header : new String[] { "attacker.invalid", "999.0.0.1", "192.0.2.10,", "",
                "192.0.2.10,".repeat(17), "1".repeat(2049) }) {
            assertEquals("10.0.0.1", resolver.resolve(request(header)));
        }
        var duplicate = request("198.51.100.1");
        duplicate.addHeader("X-Forwarded-For", "192.0.2.10");
        assertEquals("10.0.0.1", resolver.resolve(duplicate));
        var realIpOnly = request(null);
        realIpOnly.addHeader("X-Real-IP", "198.51.100.1");
        assertEquals("10.0.0.1", resolver.resolve(realIpOnly));
    }

    @Test
    void ipv6AliasesResolveToTheSameRateLimitKey() {
        var resolver = new LoginClientIpResolver("::1");
        var request = request("2001:db8::2");
        request.setRemoteAddr("0:0:0:0:0:0:0:1");
        assertEquals("2001:db8:0:0:0:0:0:2", resolver.resolve(request));
    }

    @Test
    void invalidTrustedProxyConfigurationFailsInsteadOfDoingDnsResolution() {
        assertThrows(IllegalArgumentException.class, () -> new LoginClientIpResolver("proxy.example.com"));
        assertThrows(IllegalArgumentException.class, () -> new LoginClientIpResolver("10.0.0.0/8"));
        assertThrows(IllegalArgumentException.class, () -> new LoginClientIpResolver("999.0.0.1"));
    }
}
