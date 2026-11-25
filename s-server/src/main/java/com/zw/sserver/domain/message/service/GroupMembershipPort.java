package com.zw.sserver.domain.message.service;

import java.util.Set;

/**
 * 群成员查询端口。
 */
public interface GroupMembershipPort {

    boolean isMember(Long groupId, Long userId);

    Set<Long> memberIds(Long groupId);
    Set<Long> lockAndLoadMembers(Long groupId, Long senderId);
}
