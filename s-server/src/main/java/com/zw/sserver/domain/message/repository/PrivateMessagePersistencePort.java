package com.zw.sserver.domain.message.repository;

import com.zw.sserver.domain.message.model.PrivateMessage;
import com.zw.sserver.domain.message.model.PersistedMessageRef;

import java.util.Optional;

/**
 * 私聊消息持久化端口。
 */
public interface PrivateMessagePersistencePort {

    void saveImmediately(PrivateMessage message);

    Optional<PersistedMessageRef> findByClientMsgId(Long senderId, String clientMsgId);
}
