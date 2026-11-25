package com.zw.sserver.model.vo;

import lombok.Data;
import java.util.Date;

/**
 * 群聊历史消息视图对象
 */
@Data
public class GroupHistoryVO {
    private Long messageId;
    private Long groupId;
    private Long senderId;
    private String cipherContent;
    private Date sendTime;
    private Long seq;
    private Long quoteMsgId;
    private String quoteContent;
}
