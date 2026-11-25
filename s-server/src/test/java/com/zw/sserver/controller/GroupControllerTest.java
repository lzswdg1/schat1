package com.zw.sserver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.application.session.SessionCacheInvalidationService;
import com.zw.sserver.model.vo.GroupHistoryVO;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.router.CrossNodeRouter;
import com.zw.sserver.service.GroupMemberCacheService;
import com.zw.sserver.service.GroupReadCursorService;
import com.zw.sserver.service.GroupService;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class GroupControllerTest {

    private final GroupController controller = new GroupController(
            mock(GroupService.class),
            mock(GroupMemberCacheService.class),
            mock(GroupReadCursorService.class),
            mock(CrossNodeRouter.class),
            mock(InstanceRegistry.class),
            new ObjectMapper(),
            mock(SessionCacheInvalidationService.class));

    @Test
    void groupSyncUsesSameFrontendEnvelopeAsRealtimeMessages() {
        GroupHistoryVO message = new GroupHistoryVO();
        message.setMessageId(100L);
        message.setGroupId(10L);
        message.setSenderId(2L);
        message.setSeq(7L);
        message.setSendTime(new Date(1234L));
        message.setCipherContent("{\"msgType\":\"GROUP_TEXT\",\"content\":\"hello\"}");

        Map<String, Object> result = controller.toFrontendGroupMessage(message);

        assertEquals(100L, result.get("id"));
        assertEquals("group_10", result.get("sessionId"));
        assertEquals("2", result.get("senderId"));
        assertEquals("text", result.get("type"));
        assertEquals("hello", result.get("content"));
        assertEquals(7L, result.get("seq"));
        assertFalse((Boolean) result.get("recalled"));
    }
}
