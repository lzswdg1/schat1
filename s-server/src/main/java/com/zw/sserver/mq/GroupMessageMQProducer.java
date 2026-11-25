package com.zw.sserver.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.model.entity.GroupMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 群聊消息 RocketMQ 生产者
 * <p>
 * 将群消息投递到 RocketMQ Topic: schat-group-persist
 * 由 GroupMessageMQConsumer 异步消费并写入 MySQL。
 * <p>
 * 【为什么群聊用 RocketMQ 而不是 Redis Stream？】
 * 1. 群聊消息量是单聊的 N 倍（N=群成员数），Redis 内存扛不住
 * 2. RocketMQ 基于磁盘，容量不是问题
 * 3. RocketMQ 的 Consumer Group 天然支持多实例并行消费，吞吐更高
 * 4. 支持消息回溯，方便数据修复
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "schat.legacy-async-persistence", name = "enabled", havingValue = "true")
public class GroupMessageMQProducer {

    private static final String TOPIC = "schat-group-persist";

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    public GroupMessageMQProducer(RocketMQTemplate rocketMQTemplate,
                                  ObjectMapper objectMapper) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 投递群消息到 RocketMQ
     *
     * @return true 投递成功，false 投递失败（需回退同步写库）
     */
    public boolean produce(GroupMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            rocketMQTemplate.send(TOPIC, MessageBuilder.withPayload(json).build());
            log.debug("群消息投递 RocketMQ 成功: groupId={}, messageId={}",
                    message.getGroupId(), message.getMessageId());
            return true;
        } catch (Exception e) {
            log.error("群消息投递 RocketMQ 失败: groupId={}, messageId={}",
                    message.getGroupId(), message.getMessageId(), e);
            return false;
        }
    }
}
