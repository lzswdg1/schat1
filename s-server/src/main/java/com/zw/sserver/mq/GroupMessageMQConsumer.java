package com.zw.sserver.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.application.session.SessionCacheInvalidationService;
import com.zw.sserver.domain.message.service.MessageConsumeLogPort;
import com.zw.sserver.mapper.GroupMessageMapper;
import com.zw.sserver.model.entity.GroupMessage;
import com.zw.sserver.service.GroupMemberCacheService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 群聊消息 RocketMQ 消费者
 * <p>
 * 消费 Topic: schat-group-persist，将群消息写入 MySQL。
 * <p>
 * RocketMQ 自带重试机制（默认重试 16 次，指数退避），
 * 超过最大重试次数后自动进入死信队列 %DLQ%schat-group-persist-consumer。
 * 不需要像 Redis Stream 那样手动实现重试和死信逻辑。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "schat.legacy-async-persistence", name = "enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = GroupMessageMQConsumer.TOPIC,
        consumerGroup = GroupMessageMQConsumer.CONSUMER_GROUP
)
public class GroupMessageMQConsumer implements RocketMQListener<String> {

    public static final String TOPIC = "schat-group-persist";
    public static final String CONSUMER_GROUP = "schat-group-persist-consumer";
    private static final String MESSAGE_TYPE = "GROUP";

    private final GroupMessageMapper groupMessageMapper;
    private final ObjectMapper objectMapper;
    private final MessageConsumeLogPort messageConsumeLogPort;
    private final GroupMemberCacheService groupMemberCacheService;
    private final SessionCacheInvalidationService sessionCacheInvalidationService;

    public GroupMessageMQConsumer(GroupMessageMapper groupMessageMapper,
                                  ObjectMapper objectMapper,
                                  MessageConsumeLogPort messageConsumeLogPort,
                                  GroupMemberCacheService groupMemberCacheService,
                                  SessionCacheInvalidationService sessionCacheInvalidationService) {
        this.groupMessageMapper = groupMessageMapper;
        this.objectMapper = objectMapper;
        this.messageConsumeLogPort = messageConsumeLogPort;
        this.groupMemberCacheService = groupMemberCacheService;
        this.sessionCacheInvalidationService = sessionCacheInvalidationService;
    }

    @Override
    public void onMessage(String json) {
        GroupMessage message = null;
        try {
            message = objectMapper.readValue(json, GroupMessage.class);

            groupMessageMapper.insert(message);
            messageConsumeLogPort.markSuccess(message.getMessageId(), MESSAGE_TYPE, CONSUMER_GROUP);
            sessionCacheInvalidationService.evictGroupMembers(groupMemberCacheService.getMemberIds(message.getGroupId()));
            log.debug("群消息持久化成功: groupId={}, messageId={}",
                    message.getGroupId(), message.getMessageId());

        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 幂等：消息已存在，跳过（RocketMQ 可能重复投递）
            if (message != null) {
                messageConsumeLogPort.markDuplicate(message.getMessageId(), MESSAGE_TYPE, CONSUMER_GROUP);
            }
            log.warn("群消息已存在（幂等跳过）: json={}", json);
        } catch (Exception e) {
            if (message != null) {
                messageConsumeLogPort.markFailure(message.getMessageId(), MESSAGE_TYPE, CONSUMER_GROUP, e.getMessage());
            }
            log.error("群消息持久化失败: json={}", json, e);
            // 抛出异常让 RocketMQ 重试
            throw new RuntimeException("群消息持久化失败", e);
        }
    }
}
