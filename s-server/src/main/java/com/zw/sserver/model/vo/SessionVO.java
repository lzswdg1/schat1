package com.zw.sserver.model.vo;

import lombok.Data;

/**
 * 会话列表项 VO
 */
@Data
public class SessionVO {
    private String id;           // 会话 ID: 私聊="userId", 群聊="group_groupId"
    private String type;         // "private" 或 "group"
    private Long targetId;       // 私聊=对方userId, 群聊=groupId
    private String targetName;   // 私聊=对方昵称, 群聊=群名
    private String targetAvatar; // 私聊=对方头像, 群聊=群头像
    private String lastMessage;  // 最后一条消息内容摘要
    private String lastMessageId;
    private Long lastTime;       // 最后一条消息时间戳(ms)
    private Integer unreadCount; // 未读消息数
}
