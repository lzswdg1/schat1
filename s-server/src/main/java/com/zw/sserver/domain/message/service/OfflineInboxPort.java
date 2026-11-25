package com.zw.sserver.domain.message.service;

/**
 * 离线收件箱端口。
 */
public interface OfflineInboxPort {

    void add(Long userId, Long messageId, String briefContent, long timestamp);
}
