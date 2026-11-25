package com.zw.sserver.domain.message.repository;

import com.zw.sserver.domain.message.model.GroupChatMessage;
import com.zw.sserver.domain.message.model.PersistedMessageRef;

import java.util.Optional;

/**
 * 群聊消息持久化端口。
 */
public interface GroupMessagePersistencePort {

    void saveImmediately(GroupChatMessage message);

    Optional<PersistedMessageRef> findByClientMsgId(Long groupId, Long senderId, String clientMsgId);
}
