package com.zw.sserver.websocket.handler;

import com.zw.sserver.application.session.SessionCacheInvalidationService;
import com.zw.sserver.model.dto.ChatMsgDTO;
import com.zw.sserver.service.GroupMemberCacheService;
import com.zw.sserver.service.GroupReadCursorService;
import com.zw.sserver.websocket.WebSocketSender;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


/**
 * 群已读回执处理器
 * <p>
 * 处理 msgType="GROUP_READ" 的消息：
 * 将用户在该群的持久化已读 seq 单调推进。
 * <p>
 * 前端发送格式：
 * {
 *   "msgType": "GROUP_READ",
 *   "toId": 群ID,
 *   "messageId": 已读到的最新消息ID
 * }
 * <p>
 * 【与单聊 READ 的区别】
 * 单聊 READ：更新 MySQL is_read 字段 + 跨节点转发给对方
 * 群聊 GROUP_READ：更新 MySQL 游标并镜像到 Redis，不向全群广播
 * （群聊不需要告诉每个人"谁读了"，太重了）
 */
@Component
public class GroupReadReceiptHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(GroupReadReceiptHandler.class);

    private final GroupReadCursorService groupReadCursorService;
    private final GroupMemberCacheService groupMemberCacheService;
    private final SessionCacheInvalidationService sessionCacheInvalidationService;

    public GroupReadReceiptHandler(GroupReadCursorService groupReadCursorService,
                                   GroupMemberCacheService groupMemberCacheService,
                                   SessionCacheInvalidationService sessionCacheInvalidationService) {
        this.groupReadCursorService = groupReadCursorService;
        this.groupMemberCacheService = groupMemberCacheService;
        this.sessionCacheInvalidationService = sessionCacheInvalidationService;
    }

    @Override
    public String getMsgType() {
        return "GROUP_READ";
    }

    @Override
    public void handle(Session session, Long authedUid, ChatMsgDTO msgDTO, String rawJson) {
        Long groupId = msgDTO.getToId();
        Long messageId = msgDTO.getMessageId();

        if (groupId == null || messageId == null) {
            safeSend(session, "{\"msgType\":\"ERROR\",\"content\":\"GROUP_READ 缺少 groupId 或 messageId\"}");
            return;
        }

        // 校验是否是群成员
        if (!groupMemberCacheService.isMember(groupId, authedUid)) {
            return;
        }

        // 更新已读游标 + 清零未读
        groupReadCursorService.updateCursor(authedUid, groupId, messageId);
        sessionCacheInvalidationService.evictUser(authedUid);
        log.debug("群已读游标更新: userId={}, groupId={}, cursor={}", authedUid, groupId, messageId);
    }

    private void safeSend(Session session, String text) {
        WebSocketSender.send(session, text);
    }
}
