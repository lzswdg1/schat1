package com.zw.sserver.infrastructure.message;

import com.zw.sserver.domain.message.service.GroupMembershipPort;
import com.zw.sserver.service.GroupMemberCacheService;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Redis 群成员缓存适配器。
 */
@Component
public class GroupMemberCacheAdapter implements GroupMembershipPort {

    private final GroupMemberCacheService groupMemberCacheService;
    private final com.zw.sserver.mapper.GroupInfoMapper groups;
    private final com.zw.sserver.mapper.GroupMemberMapper members;

    public GroupMemberCacheAdapter(GroupMemberCacheService groupMemberCacheService,
                                  com.zw.sserver.mapper.GroupInfoMapper groups,
                                  com.zw.sserver.mapper.GroupMemberMapper members) {
        this.groupMemberCacheService = groupMemberCacheService;
        this.groups = groups;
        this.members = members;
    }

    @Override
    public boolean isMember(Long groupId, Long userId) {
        return groupMemberCacheService.isMember(groupId, userId);
    }

    @Override
    public Set<Long> memberIds(Long groupId) {
        return groupMemberCacheService.getMemberIds(groupId);
    }

    @Override
    public Set<Long> lockAndLoadMembers(Long groupId, Long senderId) {
        if (groups.selectForUpdate(groupId) == null) return Set.of();
        Set<Long> ids = new java.util.HashSet<>(members.selectMemberIds(groupId));
        return ids.contains(senderId) ? ids : Set.of();
    }
}
