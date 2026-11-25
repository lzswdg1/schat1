package com.zw.sserver.websocket.handler;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.zw.sserver.application.session.SessionCacheInvalidationService;
import com.zw.sserver.mapper.ChatMessageMapper;
import com.zw.sserver.model.dto.ChatMsgDTO;
import com.zw.sserver.model.dto.RouteMessage;
import com.zw.sserver.model.entity.ChatMessage;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.router.CrossNodeRouter;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 已读回执处理器
 * <p>
 * 处理 msgType="READ" 的消息：
 *   1. 更新 MySQL 中的已读状态：is_read → 2（已读）
 *   2. 通过 CrossNodeRouter 跨节点转发已读回执给对方
 * <p>
 * 【消息状态机】
 *   0 = 未送达  →  1 = 已送达（DELIVERED 回执触发）  →  2 = 已读（READ 回执触发）
 * <p>
 * 【安全设计】
 * receiver_id 强制使用 authedUid（Token 鉴权后的真实 ID），
 * 不信任前端传的任何 ID，防止用户伪造已读回执清除别人的未读。
 */
@Component
public class ReadReceiptHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ReadReceiptHandler.class);

    private final ChatMessageMapper chatMessageMapper;
    private final CrossNodeRouter crossNodeRouter;
    private final InstanceRegistry instanceRegistry;
    private final SessionCacheInvalidationService sessionCacheInvalidationService;

    public ReadReceiptHandler(ChatMessageMapper chatMessageMapper,
                              CrossNodeRouter crossNodeRouter,
                              InstanceRegistry instanceRegistry,
                              SessionCacheInvalidationService sessionCacheInvalidationService) {
        this.chatMessageMapper = chatMessageMapper;
        this.crossNodeRouter = crossNodeRouter;
        this.instanceRegistry = instanceRegistry;
        this.sessionCacheInvalidationService = sessionCacheInvalidationService;
    }

    @Override
    public String getMsgType() {
        return "READ";
    }

    @Override
    public void handle(Session session, Long authedUid, ChatMsgDTO msgDTO, String rawJson) {
        // 0. 参数校验
        if (msgDTO.getToId() == null || msgDTO.getMessageId() == null) {
            log.warn("🚫 [已读回执] 缺少 toId 或 messageId，忽略: userId={}", authedUid);
            return;
        }

        ChatMessage target = chatMessageMapper.selectById(msgDTO.getMessageId());
        if (target == null
                || !msgDTO.getToId().equals(target.getSenderId())
                || !authedUid.equals(target.getReceiverId())
                || target.getSeq() == null) {
            log.warn("忽略无效已读回执: userId={}, senderId={}, messageId={}",
                    authedUid, msgDTO.getToId(), msgDTO.getMessageId());
            return;
        }

        // 1. 按会话 seq 更新已读状态，不依赖分布式雪花 ID 的大小关系。
        UpdateWrapper<ChatMessage> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("sender_id", msgDTO.getToId())
                .eq("receiver_id", authedUid)   // 强制使用可信 ID
                .le("seq", target.getSeq())
                .in("is_read", 0, 1)            // 未送达或已送达 → 已读
                .set("is_read", 2);
        chatMessageMapper.update(null, updateWrapper);
        sessionCacheInvalidationService.evictPrivateConversation(authedUid, msgDTO.getToId());

        // 2. 跨节点转发已读回执给对方（新前端格式）
        // { "type": "READ_RECEIPT", "data": { "sessionId": "...", "messageIds": [...] } }
        String payload = String.format(
                "{\"type\":\"READ_RECEIPT\",\"data\":{\"sessionId\":\"%d\",\"messageIds\":[%d]}}",
                authedUid, msgDTO.getMessageId());

        RouteMessage readReceipt = new RouteMessage();
        readReceipt.setType("READ");
        readReceipt.setTargetUserId(msgDTO.getToId());
        readReceipt.setPayload(payload);
        readReceipt.setSourceInstanceId(instanceRegistry.getInstanceId());
        crossNodeRouter.route(readReceipt);
    }
}
