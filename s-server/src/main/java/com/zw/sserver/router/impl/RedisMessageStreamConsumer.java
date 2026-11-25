package com.zw.sserver.router.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.config.SchatProperties;
import com.zw.sserver.model.dto.RouteMessage;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.registry.OnlineUserRegistry;
import jakarta.websocket.Session;
import com.zw.sserver.router.MessageStreamConsumer;
import com.zw.sserver.websocket.LocalSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redis Stream 的 MessageStream 消费者实现。
 * <p>
 * 启动时：
 * <ol>
 *   <li>创建消费组 group-{instanceId}（XGROUP CREATE，MKSTREAM）</li>
 *   <li>先处理 Pending 消息（XREADGROUP 从 "0" 开始读取未确认消息）</li>
 *   <li>然后开始消费新消息（XREADGROUP 从 ">" 开始）</li>
 * </ol>
 * <p>
 * 消费循环：XREADGROUP → 反序列化 RouteMessage → LocalSessionManager.sendToUser() → XACK
 * <p>
 * 定期 XTRIM 保持 Stream 有界。
 * <p>
 * 【补丁 4】使用 StringRedisTemplate 进行 Stream 操作。
 * 【补丁 5】sendToUser() 调用依赖 LocalSessionManager 的 per-session lock 保证线程安全。
 * <p>
 * 实现 {@link SmartLifecycle} 以控制启动/停止顺序。
 */
@Slf4j
@Component
public class RedisMessageStreamConsumer implements MessageStreamConsumer, SmartLifecycle {

    static final String STREAM_KEY_PREFIX = "schat:route:";

    private final StringRedisTemplate stringRedisTemplate;
    private final LocalSessionManager localSessionManager;
    private final InstanceRegistry instanceRegistry;
    private final SchatProperties schatProperties;
    private final ObjectMapper objectMapper;
    private final OnlineUserRegistry onlineUserRegistry;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    /** XTRIM 计数器：每消费 N 批次执行一次 XTRIM */
    private static final int TRIM_INTERVAL_BATCHES = 50;

    public RedisMessageStreamConsumer(StringRedisTemplate stringRedisTemplate,
                                      LocalSessionManager localSessionManager,
                                      InstanceRegistry instanceRegistry,
                                      SchatProperties schatProperties,
                                      ObjectMapper objectMapper,
                                      OnlineUserRegistry onlineUserRegistry) {
        this.onlineUserRegistry = onlineUserRegistry;
        this.stringRedisTemplate = stringRedisTemplate;
        this.localSessionManager = localSessionManager;
        this.instanceRegistry = instanceRegistry;
        this.schatProperties = schatProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        String instanceId = instanceRegistry.getInstanceId();
        String streamKey = STREAM_KEY_PREFIX + instanceId;
        String groupName = "group-" + instanceId;
        String consumerName = "consumer-" + instanceId;

        // 1. Create consumer group (MKSTREAM if not exists)
        createConsumerGroup(streamKey, groupName);

        // 2. Start dedicated consume thread
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "msg-stream-consumer-" + instanceId);
            t.setDaemon(true);
            return t;
        });
        executor.submit(() -> consumeLoop(streamKey, groupName, consumerName));

        log.info("MessageStreamConsumer 已启动: streamKey={}, group={}, consumer={}",
                streamKey, groupName, consumerName);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("MessageStreamConsumer 已停止");
    }

    // ========== SmartLifecycle ==========

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // Start after InstanceRegistry (default phase 0), stop before it
        return 10;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    // ========== Internal ==========

    private void createConsumerGroup(String streamKey, String groupName) {
        try {
            stringRedisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupName);
            log.info("消费组创建成功: streamKey={}, group={}", streamKey, groupName);
        } catch (Exception e) {
            // Group may already exist — that's fine
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("消费组已存在: streamKey={}, group={}", streamKey, groupName);
            } else {
                log.warn("创建消费组异常（可能已存在）: streamKey={}, group={}", streamKey, groupName, e);
            }
        }
    }

    private void consumeLoop(String streamKey, String groupName, String consumerName) {
        // Phase 1: Process pending messages (recover unacked messages from crash)
        processPendingMessages(streamKey, groupName, consumerName);

        // Phase 2: Consume new messages
        int batchCount = 0;
        while (running.get()) {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(groupName, consumerName),
                        StreamReadOptions.empty().count(10).block(Duration.ofMillis(2000)),
                        StreamOffset.create(streamKey, ReadOffset.lastConsumed())
                );

                if (records != null && !records.isEmpty()) {
                    for (MapRecord<String, Object, Object> record : records) {
                        processRecord(streamKey, groupName, record);
                    }
                }

                // Periodic XTRIM
                batchCount++;
                if (batchCount >= TRIM_INTERVAL_BATCHES) {
                    trimStream(streamKey);
                    batchCount = 0;
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.error("消费循环异常: streamKey={}", streamKey, e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void processPendingMessages(String streamKey, String groupName, String consumerName) {
        log.info("开始处理 Pending 消息: streamKey={}", streamKey);
        try {
            while (running.get()) {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(groupName, consumerName),
                        StreamReadOptions.empty().count(10),
                        StreamOffset.create(streamKey, ReadOffset.from("0"))
                );

                if (records == null || records.isEmpty()) {
                    break; // No more pending messages
                }

                for (MapRecord<String, Object, Object> record : records) {
                    processRecord(streamKey, groupName, record);
                }
            }
        } catch (Exception e) {
            log.error("处理 Pending 消息异常: streamKey={}", streamKey, e);
        }
        log.info("Pending 消息处理完成: streamKey={}", streamKey);
    }

    private void processRecord(String streamKey, String groupName, MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> fields = record.getValue();
            RouteMessage routeMessage = deserializeRouteMessage(fields);

            if (routeMessage != null && routeMessage.getTargetUserId() != null) {
                String payload = routeMessage.getPayload();
                if (payload == null) {
                    // Serialize the whole RouteMessage as payload if payload field is null
                    payload = objectMapper.writeValueAsString(routeMessage);
                }
                Long userId = routeMessage.getTargetUserId();
                Session session = localSessionManager.getSession(userId);
                String connectionId = session == null ? null : (String) session.getUserProperties().get("connectionId");
                if (connectionId != null && onlineUserRegistry.isCurrentConnection(
                        userId, instanceRegistry.getInstanceId(), connectionId)) {
                    localSessionManager.sendToUser(userId, payload);
                }
            }
        } catch (Exception e) {
            // 需求 8.4: deserialization failure → log error, XACK to discard
            log.error("消息处理失败，丢弃消息: recordId={}, streamKey={}", record.getId(), streamKey, e);
        }

        // XACK regardless of success/failure (discard bad messages per 需求 8.4)
        stringRedisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
    }

    private RouteMessage deserializeRouteMessage(Map<Object, Object> fields) {
        try {
            RouteMessage msg = new RouteMessage();
            Object type = fields.get("type");
            Object targetUserId = fields.get("targetUserId");
            Object payload = fields.get("payload");
            Object sourceInstanceId = fields.get("sourceInstanceId");

            if (type != null) msg.setType(type.toString());
            if (targetUserId != null) msg.setTargetUserId(Long.parseLong(targetUserId.toString()));
            if (payload != null) msg.setPayload(payload.toString());
            if (sourceInstanceId != null) msg.setSourceInstanceId(sourceInstanceId.toString());

            return msg;
        } catch (Exception e) {
            log.error("RouteMessage 反序列化失败: fields={}", fields, e);
            return null;
        }
    }

    private void trimStream(String streamKey) {
        try {
            long maxLen = schatProperties.getRedis().getStream().getMaxLen();
            stringRedisTemplate.opsForStream().trim(streamKey, maxLen, true);
            log.debug("Stream XTRIM 完成: streamKey={}, maxLen~{}", streamKey, maxLen);
        } catch (Exception e) {
            log.error("Stream XTRIM 失败: streamKey={}", streamKey, e);
        }
    }

    // ========== Visible for testing ==========

    /**
     * Returns the stream key for a given instanceId.
     * Visible for testing (Property 10).
     */
    static String getStreamKey(String instanceId) {
        return STREAM_KEY_PREFIX + instanceId;
    }
}
