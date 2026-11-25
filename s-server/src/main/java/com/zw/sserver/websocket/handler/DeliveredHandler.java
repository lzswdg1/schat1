package com.zw.sserver.websocket.handler;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
 * 送达回执处理器
 * <p>
 * 处理 msgType="DELIVERED" 的消息：
 *   接收方客户端收到消息后，发送 DELIVERED 回执告知服务端"我收到了"。
 * <p>
 * 处理流程：
 *   1. 更新 MySQL 中消息状态：is_read 从 0（未送达）→ 1（已送达）
 *   2. 通过 CrossNodeRouter 跨节点转发送达回执给发送方
 *      发送方前端收到后可以把消息状态从"发送中"改为"已送达"（单勾→双勾）
 * <p>
 * 【消息状态机】
 *   0 = 未送达（消息已存库，但接收方还没收到）
 *   1 = 已送达（接收方客户端已收到，但还没打开聊天窗口看）
 *   2 = 已读（接收方打开聊天窗口，发送了 READ 回执）
 * <p>
 * 【与 READ 回执的区别】
 *   DELIVERED：接收方 WebSocket 收到消息后自动发送（客户端 onMessage 里触发）
 *   READ：接收方打开聊天窗口、滚动到消息位置后手动触发
 * <p>
 * 【安全设计】
 *   receiver_id 强制使用 authedUid，防止伪造送达回执。
 */
@Component
public class DeliveredHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(DeliveredHandler.class);

    private final ChatMessageMapper chatMessageMapper;
    private final CrossNodeRouter crossNodeRouter;
    private final InstanceRegistry instanceRegistry;

    public DeliveredHandler(ChatMessageMapper chatMessageMapper,
                            CrossNodeRouter crossNodeRouter,
                            InstanceRegistry instanceRegistry) {
        this.chatMessageMapper = chatMessageMapper;
        this.crossNodeRouter = crossNodeRouter;
        this.instanceRegistry = instanceRegistry;
    }

    @Override
    public String getMsgType() {
        return "DELIVERED";
    }

    @Override
    public void handle(Session session, Long authedUid, ChatMsgDTO msgDTO, String rawJson) {
        // 0. 参数校验
        if (msgDTO.getToId() == null || msgDTO.getMessageId() == null) {
            log.warn("🚫 [送达回执] 缺少 toId 或 messageId，忽略: userId={}", authedUid);
            return;
        }

        ChatMessage target = chatMessageMapper.selectById(msgDTO.getMessageId());
        if (target == null
                || !msgDTO.getToId().equals(target.getSenderId())
                || !authedUid.equals(target.getReceiverId())
                || target.getSeq() == null) {
            log.warn("忽略无效送达回执: userId={}, senderId={}, messageId={}",
                    authedUid, msgDTO.getToId(), msgDTO.getMessageId());
            return;
        }

        UpdateWrapper<ChatMessage> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("sender_id", msgDTO.getToId())    // 消息的发送方
                .eq("receiver_id", authedUid)               // 强制使用可信 ID
                .eq("message_id", target.getMessageId())
                .eq("is_read", 0)                           // 只更新未送达的
                .set("is_read", 1);                         // 标记为已送达
        chatMessageMapper.update(null, updateWrapper);

        // 2. 跨节点转发送达回执给发送方（新前端格式）
        // { "type": "DELIVERED", "data": { "messageIds": [...] } }
        String payload = String.format(
                "{\"type\":\"DELIVERED\",\"data\":{\"messageIds\":[\"%d\"]}}",
                msgDTO.getMessageId());

        RouteMessage deliveredReceipt = new RouteMessage();
        deliveredReceipt.setType("DELIVERED");
        deliveredReceipt.setTargetUserId(msgDTO.getToId());
        deliveredReceipt.setPayload(payload);
        deliveredReceipt.setSourceInstanceId(instanceRegistry.getInstanceId());
        crossNodeRouter.route(deliveredReceipt);
    }
}
