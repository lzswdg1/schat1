package com.zw.sserver.infrastructure.message;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zw.sserver.domain.message.model.PrivateMessage;
import com.zw.sserver.domain.message.model.PersistedMessageRef;
import com.zw.sserver.domain.message.repository.PrivateMessagePersistencePort;
import com.zw.sserver.mapper.ChatMessageMapper;
import com.zw.sserver.model.entity.ChatMessage;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 私聊消息持久化适配器。
 */
@Repository
public class MybatisPrivateMessagePersistenceAdapter implements PrivateMessagePersistencePort {

    private final ChatMessageMapper chatMessageMapper;

    public MybatisPrivateMessagePersistenceAdapter(ChatMessageMapper chatMessageMapper) {
        this.chatMessageMapper = chatMessageMapper;
    }

    @Override
    public void saveImmediately(PrivateMessage message) {
        chatMessageMapper.insert(toEntity(message));
    }

    @Override
    public Optional<PersistedMessageRef> findByClientMsgId(Long senderId, String clientMsgId) {
        QueryWrapper<ChatMessage> query = new QueryWrapper<>();
        query.eq("sender_id", senderId)
                .eq("client_msg_id", clientMsgId)
                .select("message_id", "seq");
        return Optional.ofNullable(chatMessageMapper.selectOne(query))
                .map(message -> new PersistedMessageRef(message.getMessageId(), message.getSeq()));
    }

    private ChatMessage toEntity(PrivateMessage message) {
        ChatMessage entity = new ChatMessage();
        entity.setMessageId(message.getMessageId());
        entity.setClientMsgId(message.getClientMsgId());
        entity.setSenderId(message.getSenderId());
        entity.setReceiverId(message.getReceiverId());
        entity.setCipherContent(message.getPayload());
        entity.setSendTime(message.getSendTime());
        entity.setIsRead(message.getStatus().getCode());
        entity.setQuoteMsgId(message.getQuoteMsgId());
        entity.setQuoteContent(message.getQuoteContent());
        entity.setSeq(message.getSeq());
        return entity;
    }
}
