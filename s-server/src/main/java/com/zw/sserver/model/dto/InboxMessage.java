package com.zw.sserver.model.dto;

import lombok.Data;

/**
 * Redis Inbox 中的简略版消息
 * <p>
 * 用于用户上线时从 Redis ZSet 拉取未落库的未读消息摘要，
 * 与 MySQL 离线消息合并后以 messageId 去重返回给客户端。
 */
@Data
public class InboxMessage {

    /** 消息 ID（雪花算法生成） */
    private Long messageId;

    /** 消息内容摘要（包含 senderId + msgType + 内容摘要） */
    private String briefContent;

    /** 消息时间戳（毫秒） */
    private long timestamp;
}
