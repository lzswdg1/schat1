package com.zw.sserver.websocket;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.model.dto.ChatMsgDTO;
import com.zw.sserver.infrastructure.message.JdbcMessageOutbox;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.registry.OnlineUserRegistry;
import com.zw.sserver.websocket.handler.MessageHandler;
import com.zw.sserver.websocket.handler.MessageHandlerRegistry;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket 核心处理枢纽 (严苛防伪造 + 并发安全 + 异步发送)
 */
@ServerEndpoint("/ws/chat/{userId}")
@Component
public class ChatEndpoint {

    private static final Logger log = LoggerFactory.getLogger(ChatEndpoint.class);

    private static ObjectMapper objectMapper;
    private static LocalSessionManager localSessionManager;
    private static OnlineUserRegistry onlineUserRegistry;
    private static InstanceRegistry instanceRegistry;
    private static JdbcMessageOutbox messageOutbox;

    /**
     * 【策略模式核心】消息处理器注册中心
     * 根据 msgType 自动路由到对应的 Handler，替代原来 onMessage 里的 if-else 链。
     */
    private static MessageHandlerRegistry messageHandlerRegistry;

    /**
     * 【限流防刷】用户级令牌桶限流器
     * 每个用户每秒最多发 10 条消息，防止恶意刷屏打爆 Redis 和 MySQL。
     */
    private static RateLimitManager rateLimitManager;

    /**
     * 【异步离线推送】独立线程池拉取离线消息并推送
     * 避免 onOpen 阻塞 Tomcat 线程，防止大量用户同时重连时线程池耗尽。
     */
    private static OfflineMessagePusher offlineMessagePusher;

    /**
     * 【Jakarta WebSocket 的坑：静态依赖注入】
     * @ServerEndpoint 每次新连接都会 new 一个 ChatEndpoint 实例，
     * 所以 Spring 的 @Autowired 字段注入不生效（new 出来的对象不归 Spring 管）。
     * 解决方案：用 static 字段 + @Autowired setter 方法。
     * Spring 容器启动时会调用一次 setter，把依赖设置到 static 字段上，
     * 之后所有 new 出来的 ChatEndpoint 实例都能通过 static 字段访问依赖。
     */
    @Autowired
    public void setDependencies(ObjectMapper objectMapper,
                                LocalSessionManager localSessionManager,
                                OnlineUserRegistry onlineUserRegistry,
                                InstanceRegistry instanceRegistry,
                                MessageHandlerRegistry messageHandlerRegistry,
                                RateLimitManager rateLimitManager,
                                OfflineMessagePusher offlineMessagePusher,
                                JdbcMessageOutbox messageOutbox) {
        ChatEndpoint.messageOutbox = messageOutbox;
        ChatEndpoint.objectMapper = objectMapper;
        ChatEndpoint.localSessionManager = localSessionManager;
        ChatEndpoint.onlineUserRegistry = onlineUserRegistry;
        ChatEndpoint.instanceRegistry = instanceRegistry;
        ChatEndpoint.messageHandlerRegistry = messageHandlerRegistry;
        ChatEndpoint.rateLimitManager = rateLimitManager;
        ChatEndpoint.offlineMessagePusher = offlineMessagePusher;
    }

    public static void sendNotice(Long userId, String message) {
        if (localSessionManager == null) {
            log.warn("📴 [系统通知] LocalSessionManager 未初始化，跳过推送 userId={}", userId);
            return;
        }
        boolean sent = localSessionManager.sendToUser(userId, message);
        if (sent) {
            log.info("🔔 [系统通知] 成功向用户 {} 推送实时通知", userId);
        } else {
            log.info("📴 [系统通知] 用户 {} 不在线或发送失败，跳过推送", userId);
        }
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("userId") String userIdStr) throws IOException {
        Map<String, List<String>> requestParameterMap = session.getRequestParameterMap();
        List<String> tokenList = requestParameterMap.get("token");

        if (tokenList == null || tokenList.isEmpty()) {
            log.warn("❌ [鉴权失败] 缺少 Token，直接断开连接");
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Missing Token"));
            return;
        }

        Long authedUid;
        try {
            String token = tokenList.get(0);
            Object loginId = StpUtil.getLoginIdByToken(token);
            if (loginId == null) throw new RuntimeException("Token 无效或已过期");

            authedUid = Long.parseLong(String.valueOf(loginId));
            if (!authedUid.equals(Long.parseLong(userIdStr))) {
                throw new RuntimeException("Token 身份与路径 userId 不匹配");
            }
        } catch (Exception e) {
            log.warn("❌ [鉴权失败] 越权拦截！原因: {}", e.getMessage());
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Invalid Token"));
            return;
        }

        // ==========================================
        //   1：将绝对可信的 authedUid 绑定到 Session 属性中，防后续伪造
        // ==========================================
        session.getUserProperties().put("uid", authedUid);

        // 处理单点登录/多端顶号
        Session oldSession = localSessionManager.getSession(authedUid);
        if (oldSession != null && oldSession.isOpen()) {
            log.info("♻️ [多端顶号] 用户 {} 在新设备登录，强制断开旧连接", authedUid);
            try {
                oldSession.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Kicked by new login"));
            } catch (IOException ignored) {}
        }

        String connectionId = java.util.UUID.randomUUID().toString();
        session.getUserProperties().put("connectionId", connectionId);
        localSessionManager.addSession(authedUid, session);
        onlineUserRegistry.userOnline(authedUid, instanceRegistry.getInstanceId(), connectionId);
        try {
            messageOutbox.resumeForUser(authedUid);
        } catch (RuntimeException exception) {
            session.close(new CloseReason(CloseReason.CloseCodes.TRY_AGAIN_LATER, "Delivery recovery unavailable"));
            throw exception;
        }
        log.info("🟢 [上线通知] 用户 {} 已上线！当前在线人数：{}", authedUid, localSessionManager.getAllLocalUsers().size());

        // 异步拉取离线消息并推送（不阻塞 Tomcat 线程）
        offlineMessagePusher.pushAsync(session, authedUid);
    }

    /**
     * 消息入口 — 策略模式重构后的精简版
     * <p>
     * 职责只有三件事：
     *   1. 身份校验（从 Session 取 authedUid）
     *   2. JSON 解析
     *   3. 路由到对应的 MessageHandler
     * <p>
     * 具体的业务逻辑全部下沉到各个 Handler 中：
     *   - HeartbeatHandler：处理 HEARTBEAT / PING
     *   - ReadReceiptHandler：处理 READ 已读回执
     *   - ChatMessageHandler：处理 TEXT / IMAGE 等聊天消息（默认 fallback）
     */
    /**
     * 消息入口 — 兼容新前端信封格式 + 老前端扁平格式
     * <p>
     * 新前端格式（信封）：{ "type": "SEND_MESSAGE", "data": { "clientMsgId": "...", "sessionId": "...", ... } }
     * 老前端格式（扁平）：{ "msgType": "TEXT", "fromId": 1, "toId": 2, "content": "..." }
     * <p>
     * 解析策略：
     *   1. 先解析为 JsonNode
     *   2. 如果有 "data" 字段 → 新前端信封格式，从 data 中提取字段并映射到 ChatMsgDTO
     *   3. 如果没有 "data" 字段 → 老前端扁平格式，直接反序列化为 ChatMsgDTO
     */
    @OnMessage
    public void onMessage(String messageJson, Session session) {
        // 1. 身份校验
        Long authedUid = (Long) session.getUserProperties().get("uid");
        if (authedUid == null) {
            safeSend(session, "{\"type\":\"ERROR\",\"data\":{\"message\":\"非法会话，请重新登录\"}}");
            return;
        }

        String connectionId = (String) session.getUserProperties().get("connectionId");
        if (connectionId == null || !onlineUserRegistry.isCurrentConnection(
                authedUid, instanceRegistry.getInstanceId(), connectionId)) {
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Connection superseded"));
            } catch (IOException exception) {
                log.debug("关闭旧连接失败", exception);
            }
            return;
        }

        // 2. JSON 解析：兼容新旧两种格式
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = objectMapper.readTree(messageJson);
        } catch (JsonProcessingException e) {
            safeSend(session, "{\"type\":\"ERROR\",\"data\":{\"message\":\"消息格式错误：无法解析JSON\"}}");
            return;
        }

        if (root == null || !root.isObject()) {
            safeSend(session, "{\"type\":\"ERROR\",\"data\":{\"message\":\"消息格式必须为对象\"}}");
            return;
        }
        String incomingType = root.has("data") && root.has("type")
                ? root.path("type").asText() : root.path("msgType").asText();
        boolean receipt = java.util.Set.of("DELIVERY_ACK", "DELIVERED", "READ", "READ_RECEIPT", "GROUP_READ")
                .contains(incomingType.toUpperCase(java.util.Locale.ROOT));
        if (!(receipt ? rateLimitManager.tryAcquireReceipt(authedUid) : rateLimitManager.tryAcquire(authedUid))) {
            safeSend(session, "{\"type\":\"ERROR\",\"data\":{\"message\":\"发送过于频繁，请稍后再试\"}}");
            return;
        }

        String msgType;
        ChatMsgDTO msgDTO;
        String handlerJson; // 传给 Handler 的 JSON（老格式传原始 JSON，新格式传 data 部分）

        if (root.has("data") && root.has("type")) {
            // ===== 新前端信封格式 =====
            String envelopeType = root.get("type").asText();

            // 心跳：无 data 体，直接处理
            if ("HEARTBEAT".equalsIgnoreCase(envelopeType) || "PING".equalsIgnoreCase(envelopeType)) {
                MessageHandler hbHandler = messageHandlerRegistry.getHandler("HEARTBEAT");
                if (hbHandler != null) hbHandler.handle(session, authedUid, new ChatMsgDTO(), messageJson);
                return;
            }

            com.fasterxml.jackson.databind.JsonNode dataNode = root.get("data");
            if (dataNode == null || dataNode.isNull()) {
                safeSend(session, "{\"type\":\"ERROR\",\"data\":{\"message\":\"缺少 data 字段\"}}");
                return;
            }

            // 将新前端字段映射到 ChatMsgDTO
            msgDTO = new ChatMsgDTO();
            msgDTO.setClientMsgId(getTextOrNull(dataNode, "clientMsgId"));
            msgDTO.setContent(getTextOrNull(dataNode, "content"));

            // senderId → fromId（新前端传 String，需要转 Long）
            String senderIdStr = getTextOrNull(dataNode, "senderId");
            if (senderIdStr != null) {
                try { msgDTO.setFromId(Long.parseLong(senderIdStr)); } catch (NumberFormatException ignored) {}
            }

            // sessionId → toId / groupId
            // 私聊 sessionId 是对方 userId（纯数字），群聊是 "group_100"
            String sessionId = getTextOrNull(dataNode, "sessionId");
            if (sessionId != null) {
                if (sessionId.startsWith("group_")) {
                    try { msgDTO.setGroupId(Long.parseLong(sessionId.substring(6))); } catch (NumberFormatException ignored) {}
                } else {
                    try { msgDTO.setToId(Long.parseLong(sessionId)); } catch (NumberFormatException ignored) {}
                }
            }

            // quote → quoteMsgId + quoteContent
            com.fasterxml.jackson.databind.JsonNode quoteNode = dataNode.get("quote");
            if (quoteNode != null && !quoteNode.isNull()) {
                if (quoteNode.has("messageId")) {
                    msgDTO.setQuoteMsgId(quoteNode.get("messageId").asLong());
                }
                if (quoteNode.has("content")) {
                    msgDTO.setQuoteContent(quoteNode.get("content").asText());
                }
            }

            // 根据信封 type + data.type 确定 msgType
            String dataType = getTextOrNull(dataNode, "type"); // "text", "image", etc.
            msgType = mapEnvelopeToMsgType(envelopeType, dataType, msgDTO.getGroupId() != null);

            msgDTO.setMsgType(msgType);
            handlerJson = dataNode.toString();

        } else if (root.has("type") && !root.has("data")) {
            // ===== 新前端无 data 的简单消息（如心跳 {"type":"HEARTBEAT"}）=====
            String envelopeType = root.get("type").asText();
            if ("HEARTBEAT".equalsIgnoreCase(envelopeType) || "PING".equalsIgnoreCase(envelopeType)) {
                MessageHandler hbHandler = messageHandlerRegistry.getHandler("HEARTBEAT");
                if (hbHandler != null) hbHandler.handle(session, authedUid, new ChatMsgDTO(), messageJson);
                return;
            }
            // 其他无 data 的消息，尝试按老格式解析
            try {
                msgDTO = objectMapper.readValue(messageJson, ChatMsgDTO.class);
            } catch (JsonProcessingException e) {
                safeSend(session, "{\"type\":\"ERROR\",\"data\":{\"message\":\"消息格式错误\"}}");
                return;
            }
            msgType = msgDTO.getMsgType();
            handlerJson = messageJson;
        } else {
            // ===== 老前端扁平格式 =====
            try {
                msgDTO = objectMapper.readValue(messageJson, ChatMsgDTO.class);
            } catch (JsonProcessingException e) {
                safeSend(session, "{\"type\":\"ERROR\",\"data\":{\"message\":\"消息格式错误：无法解析JSON\"}}");
                return;
            }
            msgType = msgDTO.getMsgType();
            handlerJson = messageJson;
        }

        // 2.5 消息体大小校验
        String content = msgDTO.getContent();
        if (content != null && content.length() > 5000) {
            log.warn("🚫 [消息过大] 用户 {} 发送的 content 长度 {}，超过 5000 上限", authedUid, content.length());
            safeSend(session, "{\"type\":\"ERROR\",\"data\":{\"message\":\"消息内容过长，最多 5000 字符\"}}");
            return;
        }

        // 3. fromId 安全校验：防身份伪造
        if (msgDTO.getFromId() == null) {
            msgDTO.setFromId(authedUid);
        } else if (!authedUid.equals(msgDTO.getFromId())) {
            log.warn("🚨 [安全拦截] 用户 {} 企图伪造 fromId={}", authedUid, msgDTO.getFromId());
            safeSend(session, "{\"type\":\"ERROR\",\"data\":{\"message\":\"非法请求：发件人身份伪造拦截\"}}");
            return;
        }

        // PING 也当心跳处理
        if ("PING".equalsIgnoreCase(msgType)) {
            msgType = "HEARTBEAT";
        }

        // 4. 策略路由
        MessageHandler handler = messageHandlerRegistry.getHandler(msgType);
        if (handler != null) {
            handler.handle(session, authedUid, msgDTO, handlerJson);
        } else if (msgType != null && msgType.startsWith("GROUP_")) {
            MessageHandler groupFallback = messageHandlerRegistry.getHandler("GROUP_TEXT");
            if (groupFallback != null) {
                groupFallback.handle(session, authedUid, msgDTO, handlerJson);
            } else {
                safeSend(session, "{\"type\":\"ERROR\",\"data\":{\"message\":\"群聊功能未就绪\"}}");
            }
        } else {
            MessageHandler fallback = messageHandlerRegistry.getHandler("TEXT");
            if (fallback != null) {
                fallback.handle(session, authedUid, msgDTO, handlerJson);
            } else {
                safeSend(session, "{\"type\":\"ERROR\",\"data\":{\"message\":\"未知消息类型：" + msgType + "\"}}");
            }
        }
    }

    /**
     * 将新前端信封 type + data.type 映射为内部 msgType
     * <p>
     * 新前端信封 type:
     *   SEND_MESSAGE → 发消息（私聊/群聊根据 groupId 判断）
     *   READ_RECEIPT → 已读回执
     *   DELIVERED   → 送达回执
     * <p>
     * data.type: "text", "image", "file" 等
     */
    private String mapEnvelopeToMsgType(String envelopeType, String dataType, boolean isGroup) {
        if (envelopeType == null) return "TEXT";

        switch (envelopeType.toUpperCase()) {
            case "SEND_MESSAGE":
                String base = (dataType != null) ? dataType.toUpperCase() : "TEXT";
                return isGroup ? "GROUP_" + base : base;
            case "READ_RECEIPT":
                return "READ";
            case "DELIVERED":
                return "DELIVERED";
            case "HEARTBEAT":
            case "PING":
                return "HEARTBEAT";
            default:
                return envelopeType.toUpperCase();
        }
    }

    private String getTextOrNull(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode child = node.get(field);
        if (child == null || child.isNull()) return null;
        return child.asText();
    }

    private void safeSend(Session session, String text) {
        WebSocketSender.send(session, text);
    }

    @OnClose
    public void onClose(Session session) {
        Long authedUid = (Long) session.getUserProperties().get("uid");
        if (authedUid != null) {
            Session currentSession = localSessionManager.getSession(authedUid);
            if (currentSession != null && currentSession.equals(session)) {
                localSessionManager.removeSession(authedUid, session);
                try {
                    String connectionId = (String) session.getUserProperties().get("connectionId");
                    if (connectionId != null) {
                        onlineUserRegistry.userOffline(authedUid, instanceRegistry.getInstanceId(), connectionId);
                    }
                } catch (Exception e) {
                    // 停机时 Redis 可能已关闭，忽略
                }
                rateLimitManager.remove(authedUid);
                log.info("⭕ [下线通知] 用户 {} 已正常下线！当前在线人数：{}", authedUid, localSessionManager.getAllLocalUsers().size());
            }
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("❌ [连接异常]", error);
    }
}
