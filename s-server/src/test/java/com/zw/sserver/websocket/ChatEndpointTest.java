package com.zw.sserver.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.model.dto.ChatMsgDTO;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.registry.OnlineUserRegistry;
import com.zw.sserver.websocket.handler.MessageHandler;
import com.zw.sserver.websocket.handler.MessageHandlerRegistry;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatEndpoint 单元测试（策略模式重构后）
 * <p>
 * 测试 onMessage 的路由逻辑：身份校验 → 限流 → JSON 解析 → Handler 路由
 * 测试 onClose 的清理行为
 */
class ChatEndpointTest {

    private static final ObjectMapper realObjectMapper = new ObjectMapper();

    private LocalSessionManager localSessionManager;
    private OnlineUserRegistry onlineUserRegistry;
    private InstanceRegistry instanceRegistry;
    private MessageHandlerRegistry messageHandlerRegistry;
    private RateLimitManager rateLimitManager;
    private OfflineMessagePusher offlineMessagePusher;
    private com.zw.sserver.infrastructure.message.JdbcMessageOutbox messageOutbox;

    private Object origObjectMapper;
    private Object origLocalSessionManager;
    private Object origOnlineUserRegistry;
    private Object origInstanceRegistry;
    private Object origMessageHandlerRegistry;
    private Object origRateLimitManager;
    private Object origOfflineMessagePusher;
    private Object origMessageOutbox;

    @BeforeEach
    void setUp() throws Exception {
        origObjectMapper = getStaticField("objectMapper");
        origLocalSessionManager = getStaticField("localSessionManager");
        origOnlineUserRegistry = getStaticField("onlineUserRegistry");
        origInstanceRegistry = getStaticField("instanceRegistry");
        origMessageHandlerRegistry = getStaticField("messageHandlerRegistry");
        origRateLimitManager = getStaticField("rateLimitManager");
        origOfflineMessagePusher = getStaticField("offlineMessagePusher");
        origMessageOutbox = getStaticField("messageOutbox");

        localSessionManager = mock(LocalSessionManager.class);
        onlineUserRegistry = mock(OnlineUserRegistry.class);
        instanceRegistry = mock(InstanceRegistry.class);
        messageHandlerRegistry = mock(MessageHandlerRegistry.class);
        rateLimitManager = mock(RateLimitManager.class);
        offlineMessagePusher = mock(OfflineMessagePusher.class);
        messageOutbox = mock(com.zw.sserver.infrastructure.message.JdbcMessageOutbox.class);

        setStaticField("objectMapper", realObjectMapper);
        setStaticField("localSessionManager", localSessionManager);
        setStaticField("onlineUserRegistry", onlineUserRegistry);
        setStaticField("instanceRegistry", instanceRegistry);
        setStaticField("messageHandlerRegistry", messageHandlerRegistry);
        setStaticField("rateLimitManager", rateLimitManager);
        setStaticField("offlineMessagePusher", offlineMessagePusher);
        setStaticField("messageOutbox", messageOutbox);

        lenient().when(instanceRegistry.getInstanceId()).thenReturn("test-node:8082");
        lenient().when(localSessionManager.getAllLocalUsers()).thenReturn(Set.of(1L));
        lenient().when(rateLimitManager.tryAcquire(anyLong())).thenReturn(true);
        lenient().when(rateLimitManager.tryAcquireReceipt(anyLong())).thenReturn(true);
        lenient().when(onlineUserRegistry.isCurrentConnection(anyLong(), anyString(), anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        setStaticField("objectMapper", origObjectMapper);
        setStaticField("localSessionManager", origLocalSessionManager);
        setStaticField("onlineUserRegistry", origOnlineUserRegistry);
        setStaticField("instanceRegistry", origInstanceRegistry);
        setStaticField("messageHandlerRegistry", origMessageHandlerRegistry);
        setStaticField("rateLimitManager", origRateLimitManager);
        setStaticField("offlineMessagePusher", origOfflineMessagePusher);
        setStaticField("messageOutbox", origMessageOutbox);
    }

    private static Object getStaticField(String fieldName) throws Exception {
        Field field = ChatEndpoint.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStaticField(String fieldName, Object value) throws Exception {
        Field field = ChatEndpoint.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private Session createMockSession(Long uid) throws Exception {
        Session session = mock(Session.class);
        Map<String, Object> userProps = new HashMap<>();
        userProps.put("uid", uid);
        userProps.put("connectionId", "connection-test");
        when(session.getUserProperties()).thenReturn(userProps);
        when(session.isOpen()).thenReturn(true);
        RemoteEndpoint.Basic basicRemote = mock(RemoteEndpoint.Basic.class);
        when(session.getBasicRemote()).thenReturn(basicRemote);
        return session;
    }

    // ========== Test 1: TEXT message routes to TEXT handler ==========

    @Test
    @DisplayName("TEXT message routes to registered TEXT handler")
    void textMessageRoutesToHandler() throws Exception {
        Long senderId = 1L;
        Session session = createMockSession(senderId);

        MessageHandler textHandler = mock(MessageHandler.class);
        when(messageHandlerRegistry.getHandler("TEXT")).thenReturn(textHandler);

        ChatMsgDTO msgDTO = new ChatMsgDTO();
        msgDTO.setMsgType("TEXT");
        msgDTO.setFromId(senderId);
        msgDTO.setToId(2L);
        msgDTO.setClientMsgId("client-msg-001");
        msgDTO.setContent("Hello World");
        String messageJson = realObjectMapper.writeValueAsString(msgDTO);

        ChatEndpoint endpoint = new ChatEndpoint();
        endpoint.onMessage(messageJson, session);

        verify(textHandler).handle(eq(session), eq(senderId), any(ChatMsgDTO.class), eq(messageJson));
    }

    // ========== Test 2: READ message routes to READ handler ==========

    @Test
    @DisplayName("READ message routes to registered READ handler")
    void readMessageRoutesToHandler() throws Exception {
        Long readerId = 1L;
        Session session = createMockSession(readerId);

        MessageHandler readHandler = mock(MessageHandler.class);
        when(messageHandlerRegistry.getHandler("READ")).thenReturn(readHandler);

        ChatMsgDTO msgDTO = new ChatMsgDTO();
        msgDTO.setMsgType("READ");
        msgDTO.setFromId(readerId);
        msgDTO.setToId(2L);
        msgDTO.setMessageId(99999L);
        String messageJson = realObjectMapper.writeValueAsString(msgDTO);

        ChatEndpoint endpoint = new ChatEndpoint();
        endpoint.onMessage(messageJson, session);

        verify(readHandler).handle(eq(session), eq(readerId), any(ChatMsgDTO.class), eq(messageJson));
    }

    // ========== Test 3: Rate limiting rejects excessive messages ==========

    @Test
    @DisplayName("Rate limiter rejects message when tryAcquire returns false")
    void rateLimiterRejectsMessage() throws Exception {
        Long senderId = 1L;
        Session session = createMockSession(senderId);

        when(rateLimitManager.tryAcquire(senderId)).thenReturn(false);

        ChatMsgDTO msgDTO = new ChatMsgDTO();
        msgDTO.setMsgType("TEXT");
        msgDTO.setFromId(senderId);
        msgDTO.setToId(2L);
        msgDTO.setContent("spam");
        String messageJson = realObjectMapper.writeValueAsString(msgDTO);

        ChatEndpoint endpoint = new ChatEndpoint();
        endpoint.onMessage(messageJson, session);

        // Handler should NOT be called
        verify(messageHandlerRegistry, never()).getHandler(anyString());

        // Error message sent to client
        verify(session.getBasicRemote()).sendText(contains("发送过于频繁"));
    }

    // ========== Test 4: fromId spoofing is rejected ==========

    @Test
    @DisplayName("fromId spoofing: rejects message when fromId != authedUid")
    void fromIdSpoofingRejected() throws Exception {
        Long realUid = 1L;
        Long fakeFromId = 999L;
        Session session = createMockSession(realUid);

        ChatMsgDTO msgDTO = new ChatMsgDTO();
        msgDTO.setMsgType("TEXT");
        msgDTO.setFromId(fakeFromId);
        msgDTO.setToId(2L);
        msgDTO.setContent("spoofed");
        String messageJson = realObjectMapper.writeValueAsString(msgDTO);

        ChatEndpoint endpoint = new ChatEndpoint();
        endpoint.onMessage(messageJson, session);

        // Handler should NOT be called
        verify(messageHandlerRegistry, never()).getHandler(anyString());

        // Security error sent
        verify(session.getBasicRemote()).sendText(contains("身份伪造"));
    }

    // ========== Test 5: OnClose calls removeSession + userOffline ==========

    @Test
    @DisplayName("OnClose: calls localSessionManager.removeSession() and onlineUserRegistry.userOffline()")
    void onCloseCallsRemoveSessionAndUserOffline() throws Exception {
        Long userId = 1L;
        Session session = createMockSession(userId);

        when(localSessionManager.getSession(userId)).thenReturn(session);

        ChatEndpoint endpoint = new ChatEndpoint();
        endpoint.onClose(session);

        verify(localSessionManager).removeSession(userId, session);
        verify(onlineUserRegistry).userOffline(userId, "test-node:8082", "connection-test");
        verify(rateLimitManager).remove(userId);
    }

    @Test
    @DisplayName("OnClose: does NOT remove session if current session is different (multi-device)")
    void onCloseDoesNotRemoveIfSessionMismatch() throws Exception {
        Long userId = 1L;
        Session closingSession = createMockSession(userId);
        Session currentSession = createMockSession(userId);

        when(localSessionManager.getSession(userId)).thenReturn(currentSession);

        ChatEndpoint endpoint = new ChatEndpoint();
        endpoint.onClose(closingSession);

        verify(localSessionManager, never()).removeSession(anyLong(), any());
        verify(onlineUserRegistry, never()).userOffline(anyLong(), anyString(), anyString());
    }

    @Test
    void supersededConnectionCannotSendAnotherMessage() throws Exception {
        Session session = createMockSession(1L);
        when(onlineUserRegistry.isCurrentConnection(1L, "test-node:8082", "connection-test")).thenReturn(false);
        new ChatEndpoint().onMessage("{\"msgType\":\"TEXT\",\"toId\":2,\"content\":\"old\"}", session);
        verify(session).close(any());
        verifyNoInteractions(messageHandlerRegistry);
        verify(rateLimitManager, never()).tryAcquire(anyLong());
    }

    @Test
    void authenticatedReconnectResumesDeliveryWorkBeforeOfflineReplay() throws Exception {
        Session session = createMockSession(1L);
        when(session.getRequestParameterMap()).thenReturn(Map.of("token", List.of("valid-token")));
        try (var tokens = mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            tokens.when(() -> cn.dev33.satoken.stp.StpUtil.getLoginIdByToken("valid-token")).thenReturn("1");
            new ChatEndpoint().onOpen(session, "1");
        }
        var order = inOrder(onlineUserRegistry, messageOutbox, offlineMessagePusher);
        order.verify(onlineUserRegistry).userOnline(eq(1L), eq("test-node:8082"), anyString());
        order.verify(messageOutbox).resumeForUser(1L);
        order.verify(offlineMessagePusher).pushAsync(session, 1L);
    }

    @Test
    void deliveryAcknowledgementUsesReceiptBudgetEvenWhenChatBudgetIsExhausted() throws Exception {
        Session session = createMockSession(1L);
        when(rateLimitManager.tryAcquire(1L)).thenReturn(false);
        MessageHandler receipt = mock(MessageHandler.class);
        when(messageHandlerRegistry.getHandler("DELIVERY_ACK")).thenReturn(receipt);
        new ChatEndpoint().onMessage("{\"msgType\":\"DELIVERY_ACK\",\"messageId\":\"123\",\"deliveryRevision\":1}", session);
        verify(rateLimitManager).tryAcquireReceipt(1L);
        verify(rateLimitManager, never()).tryAcquire(1L);
        verify(receipt).handle(eq(session), eq(1L), any(), anyString());
    }

    @Test
    void failedReconnectRecoveryClosesConnectionSoTheClientCanRetry() throws Exception {
        Session session = createMockSession(1L);
        when(session.getRequestParameterMap()).thenReturn(Map.of("token", List.of("valid-token")));
        doThrow(new IllegalStateException("database unavailable")).when(messageOutbox).resumeForUser(1L);
        try (var tokens = mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            tokens.when(() -> cn.dev33.satoken.stp.StpUtil.getLoginIdByToken("valid-token")).thenReturn("1");
            assertThrows(IllegalStateException.class, () -> new ChatEndpoint().onOpen(session, "1"));
        }
        verify(session).close(any());
        verifyNoInteractions(offlineMessagePusher);
    }
}
