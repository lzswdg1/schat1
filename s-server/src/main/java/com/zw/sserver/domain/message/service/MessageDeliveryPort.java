package com.zw.sserver.domain.message.service;

/**
 * 实时消息投递端口。
 */
public interface MessageDeliveryPort {

    boolean deliverPrivate(Long targetUserId, String payload);
}
