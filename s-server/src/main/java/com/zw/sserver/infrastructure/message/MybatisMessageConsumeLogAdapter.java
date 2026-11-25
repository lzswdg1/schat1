package com.zw.sserver.infrastructure.message;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zw.sserver.domain.message.service.MessageConsumeLogPort;
import com.zw.sserver.mapper.MessageConsumeLogMapper;
import com.zw.sserver.model.entity.MessageConsumeLog;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.Date;

/**
 * MQ 消费状态日志 MyBatis 适配器。
 */
@Repository
public class MybatisMessageConsumeLogAdapter implements MessageConsumeLogPort {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_DUPLICATE = "DUPLICATE";
    public static final String STATUS_FAILURE = "FAILURE";

    private final MessageConsumeLogMapper messageConsumeLogMapper;

    public MybatisMessageConsumeLogAdapter(MessageConsumeLogMapper messageConsumeLogMapper) {
        this.messageConsumeLogMapper = messageConsumeLogMapper;
    }

    @Override
    public void markSuccess(Long messageId, String messageType, String consumerGroup) {
        record(messageId, messageType, consumerGroup, STATUS_SUCCESS, null);
    }

    @Override
    public void markDuplicate(Long messageId, String messageType, String consumerGroup) {
        record(messageId, messageType, consumerGroup, STATUS_DUPLICATE, null);
    }

    @Override
    public void markFailure(Long messageId, String messageType, String consumerGroup, String reason) {
        record(messageId, messageType, consumerGroup, STATUS_FAILURE, reason);
    }

    private void record(Long messageId,
                        String messageType,
                        String consumerGroup,
                        String status,
                        String reason) {
        if (messageId == null || consumerGroup == null) {
            return;
        }
        Date now = new Date();
        MessageConsumeLog existing = find(messageId, consumerGroup);
        if (existing == null) {
            MessageConsumeLog log = new MessageConsumeLog();
            log.setMessageId(messageId);
            log.setMessageType(messageType);
            log.setConsumerGroup(consumerGroup);
            log.setStatus(status);
            log.setRetryCount(0);
            log.setFailReason(limitReason(reason));
            log.setConsumeTime(now);
            log.setCreateTime(now);
            log.setUpdateTime(now);
            try {
                messageConsumeLogMapper.insert(log);
                return;
            } catch (DuplicateKeyException ignored) {
                existing = find(messageId, consumerGroup);
            }
        }
        if (existing != null) {
            existing.setMessageType(messageType);
            existing.setStatus(status);
            existing.setRetryCount(existing.getRetryCount() == null ? 1 : existing.getRetryCount() + 1);
            existing.setFailReason(limitReason(reason));
            existing.setConsumeTime(now);
            existing.setUpdateTime(now);
            messageConsumeLogMapper.updateById(existing);
        }
    }

    private MessageConsumeLog find(Long messageId, String consumerGroup) {
        QueryWrapper<MessageConsumeLog> query = new QueryWrapper<>();
        query.eq("message_id", messageId)
                .eq("consumer_group", consumerGroup)
                .last("LIMIT 1");
        return messageConsumeLogMapper.selectOne(query);
    }

    private String limitReason(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() > 512 ? reason.substring(0, 512) : reason;
    }
}
