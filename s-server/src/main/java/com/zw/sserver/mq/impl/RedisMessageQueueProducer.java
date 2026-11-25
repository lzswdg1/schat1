package com.zw.sserver.mq.impl;

import com.zw.sserver.config.SchatProperties;
import com.zw.sserver.model.entity.ChatMessage;
import com.zw.sserver.mq.MessageQueueProducer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 Redis Stream 的消息持久化队列生产者实现。
 * <p>
 * 将 {@link ChatMessage} 序列化为 Stream record fields，XADD 到 {@code schat:mq:persist}。
 * 使用 MAXLEN 裁剪策略防止 Stream 无限增长。
 * <p>
 * 【补丁 4】使用 {@link StringRedisTemplate} 进行 Stream 操作，避免 Key 乱码。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "schat.legacy-async-persistence", name = "enabled", havingValue = "true")
@Data
public class RedisMessageQueueProducer implements MessageQueueProducer {

    private static final String STREAM_KEY = "schat:mq:persist";

    private final StringRedisTemplate stringRedisTemplate;
    private final SchatProperties schatProperties;

    @Override
    public boolean produce(ChatMessage message) {
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("messageId", String.valueOf(message.getMessageId()));
            fields.put("clientMsgId", message.getClientMsgId() != null ? message.getClientMsgId() : "");
            fields.put("senderId", String.valueOf(message.getSenderId()));
            fields.put("receiverId", String.valueOf(message.getReceiverId()));
            fields.put("cipherContent", message.getCipherContent() != null ? message.getCipherContent() : "");
            fields.put("sendTime", String.valueOf(message.getSendTime() != null ? message.getSendTime().getTime() : 0));
            if (message.getSeq() != null) {
                fields.put("seq", String.valueOf(message.getSeq()));
            }

            // 可选字段
            if (message.getQuoteMsgId() != null) {
                fields.put("quoteMsgId", String.valueOf(message.getQuoteMsgId()));
            }
            if (message.getQuoteContent() != null) {
                fields.put("quoteContent", message.getQuoteContent());
            }

            StringRecord record = StreamRecords.string(fields).withStreamKey(STREAM_KEY);

            long maxLen = schatProperties.getRedis().getStream().getMaxLen();
            RecordId recordId = stringRedisTemplate.opsForStream().add(record);

            // Apply MAXLEN trimming
            stringRedisTemplate.opsForStream().trim(STREAM_KEY, maxLen);

            log.debug("消息投递到持久化队列成功: messageId={}, recordId={}", message.getMessageId(), recordId);
            return true;
        } catch (Exception e) {
            log.error("消息投递到持久化队列失败: messageId={}", message.getMessageId(), e);
            return false;
        }
    }
}
