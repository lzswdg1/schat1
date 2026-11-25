package com.zw.sserver.domain.message.service;

/**
 * 会话序号端口。
 */
public interface ConversationSequencePort {

    long nextPrivateSeq(Long senderId, Long receiverId);

    long nextGroupSeq(Long groupId);
}
