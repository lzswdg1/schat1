package com.zw.sserver.domain.message.service;

import java.util.Set;

/**
 * 群消息实时投递端口。
 */
public interface GroupMessageDeliveryPort {

    void dispatch(Long groupId, Long senderId, Set<Long> memberIds, String payload);
}
