package com.zw.suser.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Enumeration;
import java.util.Set;

@Component
public class LoginClientIpResolver {

    private final Set<String> trustedProxies = new HashSet<>();

    public LoginClientIpResolver(@Value("${schat.login.trusted-proxies:}") String proxies) {
        for (String proxy : proxies.split(",")) {
            if (proxy.isBlank()) continue;
            String address = normalize(proxy.trim());
            if (address == null) throw new IllegalArgumentException("Trusted proxies must be IP literals");
            trustedProxies.add(address);
        }
    }

    public String resolve(HttpServletRequest request) {
        String peer = normalize(request.getRemoteAddr());
        if (peer == null) return "unknown";
        if (!trustedProxies.contains(peer)) return peer;
        Enumeration<String> headers = request.getHeaders("X-Forwarded-For");
        if (!headers.hasMoreElements()) return peer;
        String forwarded = headers.nextElement();
        if (headers.hasMoreElements() || forwarded.length() > 2048) return peer;
        String[] hops = forwarded.split(",", -1);
        if (hops.length > 16) return peer;
        for (String hop : hops) {
            if (normalize(hop.trim()) == null) return peer;
        }
        String client = peer;
        for (int index = hops.length - 1; index >= 0 && trustedProxies.contains(client); index--) {
            client = normalize(hops[index].trim());
        }
        return client;
    }

    private String normalize(String value) {
        if (value == null || value.length() > 45) return null;
        boolean ipv4 = value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
        boolean ipv6 = value.contains(":") && value.matches("[0-9a-fA-F:.]+");
        if (!ipv4 && !ipv6) return null;
        try {
            if (ipv4) {
                byte[] address = new byte[4];
                String[] parts = value.split("\\.");
                for (int index = 0; index < parts.length; index++) {
                    int octet = Integer.parseInt(parts[index]);
                    if (octet > 255) return null;
                    address[index] = (byte) octet;
                }
                return InetAddress.getByAddress(address).getHostAddress();
            }
            // Only colon-containing IPv6 literals reach this parser; IPv4 never falls back to DNS.
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
