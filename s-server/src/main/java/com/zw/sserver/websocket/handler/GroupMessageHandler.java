package com.zw.sserver.websocket.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.application.message.SendGroupMessageCommand;
import com.zw.sserver.application.message.SendGroupMessageUseCase;
import com.zw.sserver.application.message.SendMessageResult;
import com.zw.sserver.model.dto.ChatMsgDTO;
import com.zw.sserver.model.dto.GroupMsgDTO;
import com.zw.sserver.websocket.WebSocketSender;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 群聊消息处理器
 * <p>
 * 处理 msgType="GROUP_TEXT" / "GROUP_IMAGE" 的群聊消息。
 * <p>
 * 处理流程：
 *   1. 校验 clientMsgId
 *   2. 校验发送者是否是群成员
 *   3. 生成雪花 ID
 *   4. 在数据库幂等约束下同步持久化
 *   5. 异步扇出到所有在线群成员（复用 CrossNodeRouter）
 *   6. 失效会话缓存，未读数按持久化 seq 重建
 *   7. 返回 ACK 给发送方
 * <p>
 * 【与单聊的区别】
 * - 单聊：数据库先落库 + 1 对 1 路由
 * - 群聊：数据库先落库 + 1 对 N 扇出 + MySQL 已读游标
 * <p>
 * 【为什么群聊不用 Inbox？】
 * 单聊 Inbox 是写扩散（每个接收者一份），群聊如果也写扩散，
 * 1000 人群发一条消息 = 1000 次 ZADD，Redis 扛不住。
 * 群聊用读扩散：消息只存一份，每个人维护已读游标。
 */
@Component
public class GroupMessageHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(GroupMessageHandler.class);

    private final ObjectMapper objectMapper;
    private final SendGroupMessageUseCase sendGroupMessageUseCase;

    public GroupMessageHandler(ObjectMapper objectMapper,
                               SendGroupMessageUseCase sendGroupMessageUseCase) {
        this.objectMapper = objectMapper;
        this.sendGroupMessageUseCase = sendGroupMessageUseCase;
    }

    @Override
    public String getMsgType() {
        return "GROUP_TEXT";
    }

    @Override
    public void handle(Session session, Long authedUid, ChatMsgDTO chatMsgDTO, String rawJson) {
        GroupMsgDTO msgDTO;
        try {
            msgDTO = objectMapper.readValue(rawJson, GroupMsgDTO.class);
        } catch (JsonProcessingException e) {
            sendError(session, "群消息格式错误");
            return;
        }

        normalizeMessage(chatMsgDTO, msgDTO);
        SendMessageResult result = sendGroupMessageUseCase.execute(new SendGroupMessageCommand(authedUid, msgDTO));
        if (result.isSuccess()) {
            sendAck(session, result);
        } else {
            sendError(session, result.getErrorMessage());
        }
    }

    private void normalizeMessage(ChatMsgDTO chatMsgDTO, GroupMsgDTO msgDTO) {
        Long groupId = msgDTO.getGroupId();
        if (groupId == null) {
            groupId = chatMsgDTO.getGroupId();
        }
        if (groupId == null) {
            groupId = chatMsgDTO.getToId();
        }

        String clientMsgId = msgDTO.getClientMsgId();
        if (clientMsgId == null || clientMsgId.trim().isEmpty()) {
            clientMsgId = chatMsgDTO.getClientMsgId();
        }

        if (msgDTO.getContent() == null) msgDTO.setContent(chatMsgDTO.getContent());
        if (msgDTO.getFromId() == null) msgDTO.setFromId(chatMsgDTO.getFromId());
        if (msgDTO.getGroupId() == null) msgDTO.setGroupId(groupId);
        if (msgDTO.getClientMsgId() == null || msgDTO.getClientMsgId().trim().isEmpty()) {
            msgDTO.setClientMsgId(clientMsgId);
        }
        if (msgDTO.getMsgType() == null) msgDTO.setMsgType(chatMsgDTO.getMsgType());
        if (msgDTO.getQuoteMsgId() == null) msgDTO.setQuoteMsgId(chatMsgDTO.getQuoteMsgId());
        if (msgDTO.getQuoteContent() == null) msgDTO.setQuoteContent(chatMsgDTO.getQuoteContent());
    }

    private void sendAck(Session session, SendMessageResult result) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("clientMsgId", result.getClientMsgId());
            data.put("messageId", String.valueOf(result.getMessageId()));
            data.put("seq", String.valueOf(result.getSeq()));
            data.put("status", result.getStatus());
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "MSG_ACK");
            envelope.put("data", data);
            safeSend(session, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.error("群消息 ACK 序列化失败", e);
            safeSend(session, "{\"type\":\"ERROR\",\"data\":{\"message\":\"服务器处理失败\"}}");
        }
    }

    private void sendError(Session session, String message) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "ERROR");
            envelope.put("data", Map.of("message", message != null ? message : "服务器处理失败"));
            safeSend(session, objectMapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            safeSend(session, "{\"type\":\"ERROR\",\"data\":{\"message\":\"服务器处理失败\"}}");
        }
    }

    private void safeSend(Session session, String text) {
        WebSocketSender.send(session, text);
    }
}
