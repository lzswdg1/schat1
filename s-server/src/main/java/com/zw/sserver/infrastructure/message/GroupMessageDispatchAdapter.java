package com.zw.sserver.infrastructure.message;

import com.zw.sserver.domain.message.service.GroupMessageDeliveryPort;
import com.zw.sserver.service.GroupMessageDispatcher;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 群消息实时扇出适配器。
 */
@Component
public class GroupMessageDispatchAdapter implements GroupMessageDeliveryPort {

    private final GroupMessageDispatcher groupMessageDispatcher;

    public GroupMessageDispatchAdapter(GroupMessageDispatcher groupMessageDispatcher) {
        this.groupMessageDispatcher = groupMessageDispatcher;
    }

    @Override
    public void dispatch(Long groupId, Long senderId, Set<Long> memberIds, String payload) {
        groupMessageDispatcher.dispatchAsync(groupId, senderId, memberIds, payload);
    }
}
