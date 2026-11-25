package com.zw.sserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.domain.session.service.SessionCachePort;
import com.zw.sserver.model.vo.SessionVO;
import com.zw.sserver.service.impl.SessionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionServiceImplTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SessionCachePort cache = mock(SessionCachePort.class);
    private final GroupReadCursorService cursors = mock(GroupReadCursorService.class);
    private final SessionServiceImpl service = new SessionServiceImpl();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(service, "sessionCachePort", cache);
        ReflectionTestUtils.setField(service, "groupReadCursorService", cursors);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(cache.get(1L)).thenReturn(null);
    }

    private void rows(String privateBody, String groupBody, boolean emptyGroup) throws Exception {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<SessionVO>>any(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<SessionVO> mapper = invocation.getArgument(1);
                    ResultSet row = mock(ResultSet.class);
                    if (sql.contains("FROM tb_chat_message m")) {
                        assertTrue(sql.contains("m.message_id"));
                        when(row.getLong("peer_id")).thenReturn(2L);
                        when(row.getLong("message_id")).thenReturn(9007199254740993L);
                        when(row.getString("peer_name")).thenReturn("peer");
                        when(row.getString("cipher_content")).thenReturn(privateBody);
                        when(row.getTimestamp("send_time")).thenReturn(new Timestamp(1700000001000L));
                    } else {
                        assertTrue(sql.contains("gm.message_id"));
                        when(row.getLong("group_id")).thenReturn(10L);
                        when(row.getString("group_name")).thenReturn("group");
                        when(row.getObject("message_id")).thenReturn(emptyGroup ? null : 9007199254740995L);
                        when(row.getString("cipher_content")).thenReturn(groupBody);
                        when(row.getTimestamp("send_time")).thenReturn(emptyGroup ? null : new Timestamp(1700000002000L));
                    }
                    return List.of(mapper.mapRow(row, 0));
                });
    }

    @Test
    void privateAndGroupPreviewsIncludeLosslessMessageIdentity() throws Exception {
        rows("{\"content\":\"private\"}", "{\"content\":\"group\"}", false);
        var sessions = service.getSessionList(1L);
        assertEquals("9007199254740995", sessions.get(0).getLastMessageId());
        assertEquals("group", sessions.get(0).getLastMessage());
        assertEquals("9007199254740993", sessions.get(1).getLastMessageId());
        assertEquals("private", sessions.get(1).getLastMessage());
        verify(cache).put(1L, sessions);
    }

    @Test
    void recallMetadataSuppressesOriginalBodyInBothSessionTypes() throws Exception {
        rows("{\"content\":\"private secret\",\"recalled\":true}",
                "{\"content\":\"group secret\",\"msgType\":\"RECALL\"}", false);
        var sessions = service.getSessionList(1L);
        assertEquals(2, sessions.size());
        assertTrue(sessions.stream().allMatch(session -> "[消息已撤回]".equals(session.getLastMessage())));
    }

    @Test
    void emptyGroupsHaveNoInventedMessageIdentity() throws Exception {
        rows("{\"content\":\"private\"}", null, true);
        var group = service.getSessionList(1L).get(1);
        assertNull(group.getLastMessageId());
        assertEquals(0L, group.getLastTime());
        assertEquals("", group.getLastMessage());
    }

    @Test
    void oldCachedPreviewsWithoutMessageIdentityAreRebuilt() throws Exception {
        SessionVO legacy = new SessionVO();
        legacy.setLastTime(1700000001000L);
        when(cache.get(1L)).thenReturn(List.of(legacy));
        rows("{\"content\":\"private\"}", null, true);
        var sessions = service.getSessionList(1L);
        assertEquals("9007199254740993", sessions.get(0).getLastMessageId());
        verify(cache).put(1L, sessions);
    }

    @Test
    void currentCachedPreviewsAvoidDatabaseQueries() {
        SessionVO current = new SessionVO();
        current.setLastTime(1700000001000L);
        current.setLastMessageId("9007199254740993");
        var cached = List.of(current);
        when(cache.get(1L)).thenReturn(cached);
        assertSame(cached, service.getSessionList(1L));
        verifyNoInteractions(jdbc, cursors);
    }
}
