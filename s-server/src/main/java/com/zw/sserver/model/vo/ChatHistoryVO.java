package com.zw.sserver.model.vo;


import lombok.Data;
import java.util.Date;

/**
 * 历史聊天记录视图对象 (屏蔽数据库敏感字段)
 */
@Data
public class ChatHistoryVO {
    private Long messageId;       // 全局游标 ID
    private Long senderId;        // 发送方
    private Long receiverId;      // 接收方
    private String cipherContent; // 完整的 JSON 消息体
    private Date sendTime;        // 发送时间
    private Integer isRead;       // 消息状态: 0-未送达, 1-已送达, 2-已读
    private Long seq;             // 会话级递增序号
    private Long quoteMsgId;      // 引用消息 ID
    private String quoteContent;  // 引用消息内容摘要
}