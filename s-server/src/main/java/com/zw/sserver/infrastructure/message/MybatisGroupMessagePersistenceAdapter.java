package com.zw.sserver.infrastructure.message;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zw.sserver.domain.message.model.GroupChatMessage;
import com.zw.sserver.domain.message.model.PersistedMessageRef;
import com.zw.sserver.domain.message.repository.GroupMessagePersistencePort;
import com.zw.sserver.mapper.GroupMessageMapper;
import com.zw.sserver.model.entity.GroupMessage;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 群聊消息持久化适配器。
 */
@Repository
public class MybatisGroupMessagePersistenceAdapter implements GroupMessagePersistencePort {

    private final GroupMessageMapper groupMessageMapper;

    public MybatisGroupMessagePersistenceAdapter(GroupMessageMapper groupMessageMapper) {
        this.groupMessageMapper = groupMessageMapper;
    }

    @Override
    public void saveImmediately(GroupChatMessage message) {
        groupMessageMapper.insert(toEntity(message));
    }

    @Override
    public Optional<PersistedMessageRef> findByClientMsgId(Long groupId, Long senderId, String clientMsgId) {
        QueryWrapper<GroupMessage> query = new QueryWrapper<>();
        query.eq("group_id", groupId)
                .eq("sender_id", senderId)
                .eq("client_msg_id", clientMsgId)
                .select("message_id", "seq");
        return Optional.ofNullable(groupMessageMapper.selectOne(query))
                .map(message -> new PersistedMessageRef(message.getMessageId(), message.getSeq()));
    }

    private GroupMessage toEntity(GroupChatMessage message) {
        GroupMessage entity = new GroupMessage();
        entity.setMessageId(message.getMessageId());
        entity.setGroupId(message.getGroupId());
        entity.setSenderId(message.getSenderId());
        entity.setClientMsgId(message.getClientMsgId());
        entity.setCipherContent(message.getPayload());
        entity.setSendTime(message.getSendTime());
        entity.setQuoteMsgId(message.getQuoteMsgId());
        entity.setQuoteContent(message.getQuoteContent());
        entity.setSeq(message.getSeq());
        return entity;
    }
}
