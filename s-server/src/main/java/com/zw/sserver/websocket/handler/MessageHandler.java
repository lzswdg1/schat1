package com.zw.sserver.websocket.handler;

import com.zw.sserver.model.dto.ChatMsgDTO;
import jakarta.websocket.Session;

/**
 * 消息处理策略接口
 * <p>
 * 每种 msgType 对应一个实现类，由 {@link MessageHandlerRegistry} 统一路由。
 * <p>
 * 【设计模式：策略模式（Strategy Pattern）】
 * 把 ChatEndpoint.onMessage 里的 if-else 拆成独立的类：
 *   - 新增消息类型？加一个 Handler 类就行，不用改 ChatEndpoint
 *   - 每个 Handler 职责单一，好测试、好维护
 */
public interface MessageHandler {

    /**
     * 返回此 Handler 处理的消息类型
     * 比如 "HEARTBEAT"、"PING"、"READ"、"TEXT"、"IMAGE" 等
     */
    String getMsgType();

    /**
     * 处理消息
     *
     * @param session    WebSocket 会话
     * @param authedUid  经过鉴权的真实用户 ID（绝对可信）
     * @param msgDTO     解析后的消息 DTO
     * @param rawJson    原始 JSON 字符串（部分场景需要转发原文）
     */
    void handle(Session session, Long authedUid, ChatMsgDTO msgDTO, String rawJson);
}
