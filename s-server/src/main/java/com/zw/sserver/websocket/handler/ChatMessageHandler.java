package com.zw.sserver.websocket.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.application.message.SendMessageResult;
import com.zw.sserver.application.message.SendPrivateMessageCommand;
import com.zw.sserver.application.message.SendPrivateMessageUseCase;
import com.zw.sserver.model.dto.ChatMsgDTO;
import com.zw.sserver.websocket.WebSocketSender;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天消息处理器（TEXT / IMAGE 等实际聊天消息）
 * <p>
 * 这是最核心的 Handler，处理流程：
 *   1. 校验 clientMsgId（防重复提交）
 *   2. 生成雪花 ID
 *   3. 同步持久化：数据库唯一索引完成原子幂等判定
 *   4. 实时转发：通过 CrossNodeRouter 路由到目标用户
 *   5. 持久化成功后返回 ACK 给发送方
 * <p>
 * 【兜底策略】
 * 并发重试遇到 DuplicateKey 时查库返回已有的 messageId；实时投递失败
 * 不回滚已经提交的消息，由客户端 seq 增量同步补偿。
 */
@Component
public class ChatMessageHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageHandler.class);

    private final ObjectMapper objectMapper;
    private final SendPrivateMessageUseCase sendPrivateMessageUseCase;

    public ChatMessageHandler(ObjectMapper objectMapper,
                              SendPrivateMessageUseCase sendPrivateMessageUseCase) {
        this.objectMapper = objectMapper;
        this.sendPrivateMessageUseCase = sendPrivateMessageUseCase;
    }

    /**
     * 默认处理 TEXT 类型。
     * IMAGE 等其他聊天类型也走同样的流程，在 ChatEndpoint 中作为 fallback 路由到这里。
     */
    @Override
    public String getMsgType() {
        return "TEXT";
    }

    @Override
    public void handle(Session session, Long authedUid, ChatMsgDTO msgDTO, String rawJson) {
        SendMessageResult result = sendPrivateMessageUseCase.execute(new SendPrivateMessageCommand(authedUid, msgDTO));
        if (result.isSuccess()) {
            safeSend(session, ackJson(result.getClientMsgId(), result.getMessageId(), result.getSeq(), result.getStatus()));
        } else {
            safeSend(session, errorJson(result.getErrorMessage()));
        }
    }

    private String ackJson(String clientMsgId, Long messageId, Long seq, String status) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("clientMsgId", clientMsgId);
            data.put("messageId", String.valueOf(messageId));
            data.put("seq", String.valueOf(seq));
            data.put("status", status);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "MSG_ACK");
            envelope.put("data", data);
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("ACK 序列化失败", e);
            return "{\"type\":\"MSG_ACK\",\"data\":{\"status\":\"unknown\"}}";
        }
    }

    private String errorJson(String message) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "ERROR");
            envelope.put("data", Map.of("message", message != null ? message : "服务器处理失败"));
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return "{\"type\":\"ERROR\",\"data\":{\"message\":\"服务器处理失败\"}}";
        }
    }

    private void safeSend(Session session, String text) {
        WebSocketSender.send(session, text);
    }
}
