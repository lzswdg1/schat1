package com.zw.sserver.websocket.handler;

import com.zw.sserver.model.dto.ChatMsgDTO;
import com.zw.sserver.websocket.WebSocketSender;
import jakarta.websocket.Session;
import org.springframework.stereotype.Component;


/**
 * 心跳消息处理器
 * <p>
 * 处理 HEARTBEAT 和 PING 两种心跳类型，统一回复 PONG。
 * <p>
 * 【为什么心跳要单独一个 Handler？】
 * 心跳是最高频的消息类型（每个客户端每 10-30 秒发一次），
 * 单独处理可以最快返回，不走任何业务逻辑。
 */
@Component
public class HeartbeatHandler implements MessageHandler {

    /**
     * 注册为 HEARTBEAT 类型。
     * PING 类型在 ChatEndpoint 中单独判断后也会走到这里。
     */
    @Override
    public String getMsgType() {
        return "HEARTBEAT";
    }

    @Override
    public void handle(Session session, Long authedUid, ChatMsgDTO msgDTO, String rawJson) {
        safeSend(session, "{\"type\":\"HEARTBEAT_ACK\"}");
    }

    private void safeSend(Session session, String text) {
        WebSocketSender.send(session, text);
    }
}
