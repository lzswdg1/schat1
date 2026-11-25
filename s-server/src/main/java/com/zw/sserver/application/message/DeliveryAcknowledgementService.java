package com.zw.sserver.application.message;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.zw.sserver.infrastructure.message.JdbcMessageOutbox;
import com.zw.sserver.mapper.ChatMessageMapper;
import com.zw.sserver.model.entity.ChatMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class DeliveryAcknowledgementService {

    private final JdbcMessageOutbox outbox;
    private final ChatMessageMapper messages;

    public DeliveryAcknowledgementService(JdbcMessageOutbox outbox, ChatMessageMapper messages) {
        this.outbox = outbox;
        this.messages = messages;
    }

    @Transactional
    public Optional<Long> acknowledge(long messageId, long recipientId) {
        return acknowledge(messageId, recipientId, 0);
    }

    @Transactional
    public Optional<Long> acknowledge(long messageId, long recipientId, int revision) {
        ChatMessage message = messages.selectById(messageId);
        if (message != null && Long.valueOf(recipientId).equals(message.getReceiverId())) {
            message = messages.selectForUpdate(messageId);
        }
        if (!outbox.acceptsAcknowledgement(messageId, recipientId, revision)) return Optional.empty();
        outbox.acknowledge(messageId, recipientId, revision);
        if (message == null || !Long.valueOf(recipientId).equals(message.getReceiverId())) return Optional.empty();
        UpdateWrapper<ChatMessage> update = new UpdateWrapper<>();
        update.eq("message_id", messageId).eq("receiver_id", recipientId).eq("is_read", 0).set("is_read", 1);
        messages.update(null, update);
        return Optional.of(message.getSenderId());
    }
}
