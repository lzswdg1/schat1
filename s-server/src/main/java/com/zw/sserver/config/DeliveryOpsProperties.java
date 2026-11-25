package com.zw.sserver.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "schat.delivery-ops")
public class DeliveryOpsProperties {

    private boolean enabled;
    private String secret = "";
    private boolean monitorEnabled = true;
    private boolean autoCleanupEnabled;
    private int retentionDays = 30;
    private int cleanupBatchSize = 100;
    private long failedThreshold = 10;
    private long backlogThreshold = 1000;
    private long oldestTaskSecondsThreshold = 300;
    private long cachePendingThreshold = 10;
    private long cacheErrorThreshold = 1;
    private long alertRepeatMs = 900000;

    @PostConstruct
    public void validate() {
        if (enabled && (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)) {
            throw new IllegalStateException("启用运维接口必须配置独立的至少 32 字节运维密钥");
        }
        if (retentionDays < 1 || retentionDays > 3650 || cleanupBatchSize < 1 || cleanupBatchSize > 1000
                || failedThreshold < 1 || backlogThreshold < 1 || oldestTaskSecondsThreshold < 1
                || cachePendingThreshold < 1 || cacheErrorThreshold < 1 || alertRepeatMs < 1000 || (autoCleanupEnabled && !enabled)) {
            throw new IllegalStateException("无效的投递运维配置");
        }
    }
}
