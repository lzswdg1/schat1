package com.zw.sserver.mq;

import com.zw.sserver.model.entity.ChatMessage;

/**
 * 消息持久化队列生产者
 * Redis Stream Key: schat:mq:persist
 */
public interface MessageQueueProducer {
    /**
     * 投递消息到持久化队列
     * @param message 聊天消息实体
     * @return true 投递成功，false 投递失败（需回退同步写库）
     */
    boolean produce(ChatMessage message);
}
