package com.zw.sserver.model.dto;

import lombok.Data;

/**
 * 群聊消息 WebSocket 报文
 * <p>
 * 前端发送群消息时的 JSON 格式：
 * {
 *   "msgType": "GROUP_TEXT",
 *   "fromId": 1,
 *   "groupId": 100,
 *   "clientMsgId": "uuid-xxx",
 *   "content": "大家好",
 *   "quoteMsgId": null,
 *   "quoteContent": null
 * }
 */
@Data
public class GroupMsgDTO {
    private String clientMsgId;
    private Long fromId;
    private Long groupId;
    private String msgType;
    private String content;
    private Long quoteMsgId;
    private String quoteContent;
    private Long messageId;

    /**
     * 群会话级递增序号
     */
    private Long seq;
}
