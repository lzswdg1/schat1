package com.zw.sserver.application.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.mapper.ChatMessageMapper;
import com.zw.sserver.mapper.GroupMemberMapper;
import com.zw.sserver.mapper.GroupMessageMapper;
import com.zw.sserver.model.entity.ChatMessage;
import com.zw.sserver.model.entity.GroupMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MessageSearchContractTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ChatMessageMapper privateMessages = mock(ChatMessageMapper.class);
    private final GroupMessageMapper groupMessages = mock(GroupMessageMapper.class);
    private final GroupMemberMapper members = mock(GroupMemberMapper.class);
    private final ChatHttpApplicationService service = new ChatHttpApplicationService(privateMessages,
            null, null, json, groupMessages, members, null, null, null, null, null, null);

    private ChatMessage privateMessage(String content) {
        var message = new ChatMessage();
        message.setMessageId(9007199254740993L);
        message.setSenderId(1L);
        message.setReceiverId(2L);
        message.setCipherContent(content);
        message.setSendTime(new Date(1700000001000L));
        return message;
    }

    private GroupMessage groupMessage(String content) {
        var message = new GroupMessage();
        message.setMessageId(9007199254740995L);
        message.setGroupId(10L);
        message.setSenderId(9007199254740997L);
        message.setCipherContent(content);
        message.setSendTime(new Date(1700000002000L));
        return message;
    }

    @BeforeEach
    void setup() {
        when(members.selectGroupIdsByUserId(1L)).thenReturn(List.of(10L));
        when(privateMessages.selectList(any())).thenReturn(List.of(privateMessage(
                "{\"msgType\":\"TEXT\",\"content\":\"needle private\"}")));
        when(groupMessages.selectList(any())).thenReturn(List.of(groupMessage(
                "{\"msgType\":\"GROUP_TEXT\",\"content\":\"needle <img src=x onerror=window.__searchXss=1>\"}")));
    }

    @Test
    void serializedResponseMatchesTheFixtureAlsoConsumedByTheFrontend() throws Exception {
        var response = service.searchMessages(1L, "needle", 50);
        try (var fixture = getClass().getResourceAsStream("/contracts/message-search-response.json")) {
            assertNotNull(fixture);
            assertEquals(json.readTree(fixture), json.readTree(json.writeValueAsString(response)));
        }
    }

    @Test
    void onlyTextBodiesMatchAndRecalledMessagesOrMetadataAreNotReturned() {
        when(privateMessages.selectList(any())).thenReturn(List.of(
                privateMessage("{\"msgType\":\"TEXT\",\"content\":\"needle recalled\",\"recalled\":true}"),
                privateMessage("{\"msgType\":\"IMAGE\",\"content\":\"needle image\"}"),
                privateMessage("{\"msgType\":\"TEXT\",\"content\":\"other\",\"quoteContent\":\"needle\"}"),
                privateMessage("needle legacy")));
        when(groupMessages.selectList(any())).thenReturn(List.of(
                groupMessage("{\"msgType\":\"RECALL\",\"content\":\"needle\"}"),
                groupMessage("{\"msgType\":\"GROUP_FILE\",\"content\":\"needle\"}")));
        var results = service.searchMessages(1L, "NEEDLE", 50).getData();
        assertEquals(1, results.size());
        assertEquals("needle legacy", results.get(0).get("content"));
    }

    @Test
    void emptyKeywordsNeverQueryMessagesOrMembership() {
        assertTrue(service.searchMessages(1L, null, 50).getData().isEmpty());
        assertTrue(service.searchMessages(1L, "  ", 50).getData().isEmpty());
        verifyNoInteractions(privateMessages, groupMessages, members);
    }

    @Test
    void missingLimitAndTimestampHaveStableDefaults() {
        var message = privateMessage("needle legacy");
        message.setSendTime(null);
        when(privateMessages.selectList(any())).thenReturn(List.of(message));
        var results = service.searchMessages(1L, "needle", null).getData();
        assertEquals(2, results.size());
        assertEquals(0L, results.get(1).get("createTime"));
        assertEquals(1, service.searchMessages(1L, "needle", 0).getData().size());
    }

    @Test
    void usersWithoutGroupsNeverQueryGroupMessageRows() {
        when(members.selectGroupIdsByUserId(1L)).thenReturn(List.of());
        var results = service.searchMessages(1L, "needle", 50).getData();
        assertEquals(1, results.size());
        assertEquals("2", results.get(0).get("sessionId"));
        verifyNoInteractions(groupMessages);
    }

    @Test
    void searchesKeepAuthenticatedUserAndMembershipPredicatesWithBoundParameters() {
        String keyword = "needle' OR 1=1 --";
        when(privateMessages.selectList(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> query = invocation.getArgument(0);
            String sql = query.getSqlSegment();
            assertTrue(sql.contains("sender_id"));
            assertTrue(sql.contains("receiver_id"));
            assertFalse(sql.contains(keyword));
            assertTrue(query.getParamNameValuePairs().containsValue(1L));
            return List.of();
        });
        when(groupMessages.selectList(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> query = invocation.getArgument(0);
            String sql = query.getSqlSegment();
            assertTrue(sql.contains("group_id"));
            assertFalse(sql.contains(keyword));
            assertTrue(query.getParamNameValuePairs().containsValue(10L));
            return List.of();
        });
        assertTrue(service.searchMessages(1L, keyword, 50).getData().isEmpty());
        verify(members).selectGroupIdsByUserId(1L);
    }
}
