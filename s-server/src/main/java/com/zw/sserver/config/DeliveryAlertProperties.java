package com.zw.sserver.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Getter
@Setter
@ConfigurationProperties(prefix = "schat.delivery-alert")
public class DeliveryAlertProperties {

    private boolean enabled;
    private String url = "";
    private String secret = "";
    private boolean allowLoopbackHttp;
    private int connectTimeoutMs = 1000;
    private int requestTimeoutMs = 3000;
    private int maxAttempts = 5;
    private long retryInitialMs = 1000;
    private long retryMaxMs = 60000;

    @PostConstruct
    public void validate() {
        if (connectTimeoutMs < 100 || connectTimeoutMs > 10000 || requestTimeoutMs < connectTimeoutMs
                || requestTimeoutMs > 15000 || maxAttempts < 1 || maxAttempts > 10
                || retryInitialMs < 100 || retryMaxMs < retryInitialMs || retryMaxMs > 300000) {
            throw new IllegalStateException("无效的投递告警超时或重试配置");
        }
        if (!enabled) return;
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("启用投递告警必须配置独立的至少 32 字节签名密钥");
        }
        try {
            URI target = URI.create(url);
            boolean localHttp = allowLoopbackHttp && "http".equalsIgnoreCase(target.getScheme())
                    && Set.of("127.0.0.1", "[::1]", "::1").contains(target.getHost());
            if (target.getHost() == null || target.getUserInfo() != null || target.getFragment() != null
                    || target.getPort() == 0 || target.getPort() > 65535
                    || (!"https".equalsIgnoreCase(target.getScheme()) && !localHttp)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("投递告警必须使用无用户信息和片段的 HTTPS 地址；明文 HTTP 仅允许显式启用的环回地址");
        }
    }
}
