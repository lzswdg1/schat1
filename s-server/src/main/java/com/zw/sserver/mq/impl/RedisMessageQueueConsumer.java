package com.zw.sserver.mq.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.application.session.SessionCacheInvalidationService;
import com.zw.sserver.config.SchatProperties;
import com.zw.sserver.mapper.ChatMessageMapper;
import com.zw.sserver.model.entity.ChatMessage;
import com.zw.sserver.mq.MessageQueueConsumer;
import com.zw.sserver.mq.RedisInboxService;
import com.zw.sserver.registry.InstanceRegistry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redis Stream 的消息持久化队列消费者实现。
 * <p>
 * 消费 {@code schat:mq:persist} Stream，将消息写入 MySQL，
 * 成功后清理 Inbox 并 XACK 确认。
 * <p>
 * 消费组：{@code persist-group}，消费者：{@code persist-consumer-{instanceId}}
 * <p>
 * 失败重试策略：最多 3 次，指数退避（1s, 2s, 4s）。
 * <p>
 * 【补丁 2】达到最大重试次数后，转移到死信队列 {@code schat:mq:deadletter} 并强制 XACK，
 * 防止 PEL 无限膨胀。
 * <p>
 * 【补丁 4】使用 {@link StringRedisTemplate} 进行 Stream 操作。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "schat.legacy-async-persistence", name = "enabled", havingValue = "true")
@Data
public class RedisMessageQueueConsumer implements MessageQueueConsumer, SmartLifecycle {

    static final String STREAM_KEY = "schat:mq:persist";
    static final String GROUP_NAME = "persist-group";
    static final String DEAD_LETTER_KEY = "schat:mq:deadletter";

    /** Base delays for exponential backoff: 1s, 2s, 4s */
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 4000};

    private final StringRedisTemplate stringRedisTemplate;
    private final ChatMessageMapper chatMessageMapper;
    private final InstanceRegistry instanceRegistry;
    private final SchatProperties schatProperties;
    private final ObjectMapper objectMapper;
    private final SessionCacheInvalidationService sessionCacheInvalidationService;

    @Autowired(required = false)
    private RedisInboxService redisInboxService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        String instanceId = instanceRegistry.getInstanceId();
        String consumerName = "persist-consumer-" + instanceId;

        // 1. Create consumer group (MKSTREAM if not exists)
        createConsumerGroup();

        // 2. Start dedicated consume thread
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mq-persist-consumer-" + instanceId);
            t.setDaemon(true);
            return t;
        });
        executor.submit(() -> consumeLoop(consumerName));

        log.info("MessageQueueConsumer 已启动: streamKey={}, group={}, consumer={}",
                STREAM_KEY, GROUP_NAME, consumerName);
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

        log.info("MessageQueueConsumer 已停止");
    }

    // ========== SmartLifecycle ==========

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // Start after InstanceRegistry (phase 0) and MessageStreamConsumer (phase 10)
        return 15;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    // ========== Internal ==========

    private void createConsumerGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP_NAME);
            log.info("消费组创建成功: streamKey={}, group={}", STREAM_KEY, GROUP_NAME);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("消费组已存在: streamKey={}, group={}", STREAM_KEY, GROUP_NAME);
            } else {
                log.warn("创建消费组异常（可能已存在）: streamKey={}, group={}", STREAM_KEY, GROUP_NAME, e);
            }
        }
    }

    private void consumeLoop(String consumerName) {
        // Phase 1: Process pending messages (recover unacked messages from crash)
        processPendingMessages(consumerName);

        // Phase 2: Consume new messages
        while (running.get()) {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, consumerName),
                        StreamReadOptions.empty().count(10).block(Duration.ofMillis(2000)),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );

                if (records != null && !records.isEmpty()) {
                    for (MapRecord<String, Object, Object> record : records) {
                        processRecordWithRetry(record, consumerName);
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.error("持久化消费循环异常: streamKey={}", STREAM_KEY, e);
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

    private void processPendingMessages(String consumerName) {
        log.info("开始处理 Pending 消息: streamKey={}", STREAM_KEY);
        try {
            while (running.get()) {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, consumerName),
                        StreamReadOptions.empty().count(10),
                        StreamOffset.create(STREAM_KEY, ReadOffset.from("0"))
                );

                if (records == null || records.isEmpty()) {
                    break;
                }

                for (MapRecord<String, Object, Object> record : records) {
                    processRecordWithRetry(record, consumerName);
                }
            }
        } catch (Exception e) {
            log.error("处理 Pending 消息异常: streamKey={}", STREAM_KEY, e);
        }
        log.info("Pending 消息处理完成: streamKey={}", STREAM_KEY);
    }

    /**
     * Process a single record with retry logic.
     * On success: INSERT MySQL → ZREM Inbox → XACK.
     * On failure after max retries: transfer to dead letter queue → force XACK (补丁 2).
     */
    void processRecordWithRetry(MapRecord<String, Object, Object> record, String consumerName) {
        int maxRetries = schatProperties.getRedis().getStream().getMaxRetries();
        ChatMessage chatMessage = null;
        Exception lastException = null;

        try {
            chatMessage = deserializeChatMessage(record.getValue());
        } catch (Exception e) {
            log.error("消息反序列化失败，丢弃消息: recordId={}", record.getId(), e);
            // XACK to discard malformed message
            stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
            return;
        }

        if (chatMessage == null) {
            log.error("消息反序列化结果为 null，丢弃消息: recordId={}", record.getId());
            stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
            return;
        }

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                // INSERT into MySQL
                chatMessageMapper.insert(chatMessage);
                sessionCacheInvalidationService.evictPrivateConversation(
                        chatMessage.getSenderId(),
                        chatMessage.getReceiverId()
                );

                // Remove from Inbox on success
                if (redisInboxService != null && chatMessage.getReceiverId() != null) {
                    try {
                        redisInboxService.removeFromInbox(chatMessage.getReceiverId(), chatMessage.getMessageId());
                    } catch (Exception inboxEx) {
                        log.warn("Inbox 清理失败（不影响消息持久化）: messageId={}, receiverId={}",
                                chatMessage.getMessageId(), chatMessage.getReceiverId(), inboxEx);
                    }
                }

                // XACK on success
                stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
                log.debug("消息持久化成功: messageId={}, recordId={}", chatMessage.getMessageId(), record.getId());
                return;

            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries - 1) {
                    long delay = RETRY_DELAYS_MS[attempt];
                    log.warn("消息持久化失败，第 {} 次重试（{}ms 后）: messageId={}, recordId={}",
                            attempt + 1, delay, chatMessage.getMessageId(), record.getId(), e);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("重试等待被中断: messageId={}", chatMessage.getMessageId());
                        break;
                    }
                }
            }
        }

        // 【补丁 2】Max retries exhausted → dead letter queue + force XACK
        log.error("消息持久化最终失败（已重试 {} 次），转移到死信队列: messageId={}, recordId={}",
                maxRetries, chatMessage.getMessageId(), record.getId(), lastException);

        transferToDeadLetterQueue(record, chatMessage, lastException, maxRetries);

        // Force XACK to remove from PEL — NEVER allow permanent PEL entries
        stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
        log.warn("死信消息已强制 XACK 从主 Stream 剔除: messageId={}, recordId={}",
                chatMessage.getMessageId(), record.getId());
    }

    /**
     * Transfer a failed message to the dead letter queue (补丁 2).
     */
    private void transferToDeadLetterQueue(MapRecord<String, Object, Object> record,
                                           ChatMessage chatMessage,
                                           Exception lastException,
                                           int retryCount) {
        try {
            String payload;
            try {
                payload = objectMapper.writeValueAsString(chatMessage);
            } catch (Exception jsonEx) {
                payload = record.getValue().toString();
            }

            Map<String, String> deadLetterFields = new LinkedHashMap<>();
            deadLetterFields.put("originalStreamKey", STREAM_KEY);
            deadLetterFields.put("originalRecordId", record.getId().getValue());
            deadLetterFields.put("messageId", String.valueOf(chatMessage.getMessageId()));
            deadLetterFields.put("payload", payload);
            deadLetterFields.put("failReason", lastException != null ? lastException.getMessage() : "unknown");
            deadLetterFields.put("retryCount", String.valueOf(retryCount));
            deadLetterFields.put("deadTime", String.valueOf(System.currentTimeMillis()));

            StringRecord deadLetterRecord = StreamRecords.string(deadLetterFields).withStreamKey(DEAD_LETTER_KEY);
            stringRedisTemplate.opsForStream().add(deadLetterRecord);

            log.info("消息已转移到死信队列: messageId={}, deadLetterKey={}", chatMessage.getMessageId(), DEAD_LETTER_KEY);
        } catch (Exception e) {
            log.error("转移到死信队列失败: messageId={}", chatMessage.getMessageId(), e);
        }
    }

    /**
     * Deserialize Stream record fields back into a ChatMessage entity.
     */
    ChatMessage deserializeChatMessage(Map<Object, Object> fields) {
        ChatMessage msg = new ChatMessage();

        Object messageId = fields.get("messageId");
        if (messageId != null) {
            msg.setMessageId(Long.parseLong(messageId.toString()));
        }

        Object clientMsgId = fields.get("clientMsgId");
        if (clientMsgId != null && !clientMsgId.toString().isEmpty()) {
            msg.setClientMsgId(clientMsgId.toString());
        }

        Object senderId = fields.get("senderId");
        if (senderId != null) {
            msg.setSenderId(Long.parseLong(senderId.toString()));
        }

        Object receiverId = fields.get("receiverId");
        if (receiverId != null) {
            msg.setReceiverId(Long.parseLong(receiverId.toString()));
        }

        Object cipherContent = fields.get("cipherContent");
        if (cipherContent != null && !cipherContent.toString().isEmpty()) {
            msg.setCipherContent(cipherContent.toString());
        }

        Object sendTime = fields.get("sendTime");
        if (sendTime != null) {
            long ts = Long.parseLong(sendTime.toString());
            if (ts > 0) {
                msg.setSendTime(new Date(ts));
            }
        }

        Object quoteMsgId = fields.get("quoteMsgId");
        if (quoteMsgId != null) {
            msg.setQuoteMsgId(Long.parseLong(quoteMsgId.toString()));
        }

        Object quoteContent = fields.get("quoteContent");
        if (quoteContent != null) {
            msg.setQuoteContent(quoteContent.toString());
        }

        Object seq = fields.get("seq");
        if (seq != null) {
            msg.setSeq(Long.parseLong(seq.toString()));
        }

        // Default isRead to 0 (unread) for newly persisted messages
        msg.setIsRead(0);

        return msg;
    }
}
