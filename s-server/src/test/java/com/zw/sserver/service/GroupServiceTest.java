package com.zw.sserver.service;

import com.zw.sserver.mapper.GroupInfoMapper;
import com.zw.sserver.mapper.GroupMemberMapper;
import com.zw.sserver.mapper.GroupMessageMapper;
import com.zw.sserver.model.entity.GroupInfo;
import com.zw.sserver.model.entity.GroupMember;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupServiceTest {

    private final GroupInfoMapper groupInfoMapper = mock(GroupInfoMapper.class);
    private final GroupMemberMapper groupMemberMapper = mock(GroupMemberMapper.class);
    private final GroupMemberCacheService cacheService = mock(GroupMemberCacheService.class);
    private final GroupService service = new GroupService(
            groupInfoMapper,
            groupMemberMapper,
            mock(GroupMessageMapper.class),
            cacheService,
            mock(GroupReadCursorService.class), mock(com.zw.sserver.infrastructure.message.JdbcMessageOutbox.class));

    @Test
    void joinUsesLockedGroupRowAndRejectsFullGroup() {
        GroupInfo group = group(10L, 3, 3);
        when(groupInfoMapper.selectForUpdate(10L)).thenReturn(group);
        when(groupMemberMapper.selectOne(any())).thenReturn(null);

        assertFalse(service.joinGroup(10L, 9L));

        verify(groupInfoMapper).selectForUpdate(10L);
        verify(groupMemberMapper, never()).insert(any());
    }

    @Test
    void inviteCannotExceedCapacityAndRepairsCountUnderLock() {
        GroupInfo group = group(10L, 2, 3);
        GroupMember inviter = new GroupMember();
        inviter.setGroupId(10L);
        inviter.setUserId(1L);
        when(groupInfoMapper.selectForUpdate(10L)).thenReturn(group);
        when(groupMemberMapper.selectOne(any())).thenReturn(inviter, null, null);

        assertTrue(service.inviteMembers(10L, 1L, List.of(2L, 3L)));

        verify(groupMemberMapper, times(1)).insert(any(GroupMember.class));
        verify(groupInfoMapper).updateById(group);
        verify(cacheService).evict(10L);
        org.junit.jupiter.api.Assertions.assertEquals(3, group.getMemberCount());
    }

    private static GroupInfo group(Long groupId, int count, int max) {
        GroupInfo group = new GroupInfo();
        group.setGroupId(groupId);
        group.setOwnerId(1L);
        group.setMemberCount(count);
        group.setMaxMembers(max);
        return group;
    }
}
