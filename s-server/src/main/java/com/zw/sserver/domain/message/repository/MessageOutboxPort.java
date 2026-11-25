package com.zw.sserver.domain.message.repository;

public interface MessageOutboxPort {

    void enqueue(long messageId, long targetUserId, String payload);
}
