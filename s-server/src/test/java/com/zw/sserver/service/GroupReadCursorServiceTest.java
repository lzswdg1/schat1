package com.zw.sserver.service;

import com.zw.sserver.mapper.GroupMessageMapper;
import com.zw.sserver.mapper.GroupMemberMapper;
import com.zw.sserver.model.entity.GroupMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupReadCursorServiceTest {

    @Test
    void validMessageAdvancesCursorThroughAtomicScript() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        GroupMessageMapper mapper = mock(GroupMessageMapper.class);
        GroupMemberMapper memberMapper = mock(GroupMemberMapper.class);
        GroupMessage message = new GroupMessage();
        message.setMessageId(100L);
        message.setGroupId(10L);
        message.setSeq(7L);
        when(mapper.selectById(100L)).thenReturn(message);
        when(memberMapper.advanceLastReadSeq(10L, 2L, 7L)).thenReturn(1);
        when(redisTemplate.execute(any(), anyList(), eq("10"), eq("7"))).thenReturn(1L);

        GroupReadCursorService service = new GroupReadCursorService(redisTemplate, mapper, memberMapper);

        assertTrue(service.updateCursor(2L, 10L, 100L));
        verify(redisTemplate).execute(any(), anyList(), eq("10"), eq("7"));
    }

    @Test
    void messageFromAnotherGroupCannotClearUnread() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        GroupMessageMapper mapper = mock(GroupMessageMapper.class);
        GroupMemberMapper memberMapper = mock(GroupMemberMapper.class);
        GroupMessage message = new GroupMessage();
        message.setMessageId(100L);
        message.setGroupId(11L);
        message.setSeq(7L);
        when(mapper.selectById(100L)).thenReturn(message);

        GroupReadCursorService service = new GroupReadCursorService(redisTemplate, mapper, memberMapper);

        assertFalse(service.updateCursor(2L, 10L, 100L));
        verify(redisTemplate, never()).execute(any(), anyList(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unreadCountIsRebuiltFromDurableCursorAndMessageTable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        GroupMessageMapper mapper = mock(GroupMessageMapper.class);
        GroupMemberMapper memberMapper = mock(GroupMemberMapper.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(memberMapper.selectLastReadSeq(10L, 2L)).thenReturn(5L);
        when(mapper.countAfterSeq(10L, 2L, 5L)).thenReturn(3L);

        GroupReadCursorService service = new GroupReadCursorService(redisTemplate, mapper, memberMapper);

        assertEquals(3, service.getUnreadCount(2L, 10L));
        verify(mapper).countAfterSeq(10L, 2L, 5L);
        verify(hashOperations).put("schat:group:cursor:v2:2", "10", "5");
    }

    @Test
    void duplicateOrLateReadReceiptCannotMoveCursorBackward() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        GroupMessageMapper mapper = mock(GroupMessageMapper.class);
        GroupMemberMapper memberMapper = mock(GroupMemberMapper.class);
        GroupMessage message = new GroupMessage();
        message.setMessageId(100L);
        message.setGroupId(10L);
        message.setSeq(7L);
        when(mapper.selectById(100L)).thenReturn(message);
        when(memberMapper.advanceLastReadSeq(10L, 2L, 7L)).thenReturn(0);

        GroupReadCursorService service = new GroupReadCursorService(redisTemplate, mapper, memberMapper);

        assertFalse(service.updateCursor(2L, 10L, 100L));
        verify(redisTemplate, never()).execute(any(), anyList(), any(), any());
    }
}
