package com.zw.sserver.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Pattern;

/**
 * SChat 全局配置属性
 * <p>
 * 【补丁 1】instance.id 必须使用物理稳定标识（K8s Pod Name 或 内网IP:端口），
 * 禁止使用随机 UUID，确保重启后能重新接管原有 Stream。
 */
@Data
@Slf4j
@ConfigurationProperties(prefix = "schat")
public class SchatProperties {

    /**
     * UUID v4 格式正则：xxxxxxxx-xxxx-4xxx-[89ab]xxx-xxxxxxxxxxxx
     * 用于检测 instance.id 是否包含随机 UUID
     */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private Instance instance = new Instance();
    private Redis redis = new Redis();
    private Heartbeat heartbeat = new Heartbeat();
    private Shutdown shutdown = new Shutdown();

    @PostConstruct
    public void validate() {
        String id = instance.getId();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException(
                    "schat.instance.id 不能为空，请配置物理稳定标识（如 hostname:port 或 K8s Pod Name）");
        }
        if (UUID_PATTERN.matcher(id).find()) {
            throw new IllegalStateException(
                    "schat.instance.id 不允许包含 UUID 格式的随机值: '" + id
                            + "'。请使用物理稳定标识（如 hostname:port 或 K8s Pod Name），"
                            + "确保实例重启后 ID 不变，能重新接管原有 Stream。");
        }
        log.info("SChat 实例 ID: {}", id);
    }

    @Data
    public static class Instance {
        /**
         * 实例唯一标识，默认值为 HOSTNAME:PORT 或 localhost:8082
         * 禁止使用随机 UUID（补丁 1）
         */
        private String id = "${HOSTNAME:${SERVER_IP:localhost}:${server.port:8082}}";
    }

    @Data
    public static class Redis {
        private Stream stream = new Stream();
        private Inbox inbox = new Inbox();

        @Data
        public static class Stream {
            /** MessageStream XTRIM 保留条数 */
            private long maxLen = 1000;
            /** MessageQueue 消费者最大重试次数 */
            private int maxRetries = 3;
        }

        @Data
        public static class Inbox {
            /** Inbox 过期时间（天） */
            private int expireDays = 7;
        }
    }

    @Data
    public static class Heartbeat {
        /** 心跳间隔（秒） */
        private int interval = 10;
        /** 心跳超时（秒） */
        private int timeout = 30;
    }

    @Data
    public static class Shutdown {
        /** 优雅停机等待时间（秒） */
        private int waitSeconds = 5;
    }
}
