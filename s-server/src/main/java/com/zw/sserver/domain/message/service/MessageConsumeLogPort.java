package com.zw.sserver.domain.message.service;

/**
 * MQ 消费状态日志端口。
 */
public interface MessageConsumeLogPort {

    void markSuccess(Long messageId, String messageType, String consumerGroup);

    void markDuplicate(Long messageId, String messageType, String consumerGroup);

    void markFailure(Long messageId, String messageType, String consumerGroup, String reason);
}
