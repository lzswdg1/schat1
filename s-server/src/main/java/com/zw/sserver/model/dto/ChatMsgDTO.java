package com.zw.sserver.model.dto;

import lombok.Data;

/**
 * 实时通讯的标准消息报文 (信封)
 */
@Data
public class ChatMsgDTO {
    private String clientMsgId; // 前端生成的本地防重唯一 ID
    private Long fromId;        // 谁发的
    private Long toId;          // 发给谁

    // 消息类型: TEXT (文本), IMAGE (图片), READ (已读回执), GROUP_TEXT, GROUP_IMAGE, GROUP_READ
    private String msgType;
    private String content;     // 聊天内容实体 (如果是图片，这里存的就是带 URL 的 JSON 字符串)

    /** 群聊专用：群 ID（群聊消息推荐用此字段，也兼容 toId） */
    private Long groupId;

    /**
     * 用于“引用回复”功能
     * 前端如果在引用某条消息，发送时会带上这两个字段
     */
    private Long quoteMsgId;     // 被引用的那条历史消息的全局 messageId
    private String quoteContent; // 被引用的消息内容摘要（让前端能直接画出灰色引用框，省去查库）

    /**
     * 用于“已读回执”功能 与 游标同步
     * 作用 1：当 msgType="READ" 时，前端传过来，代表“我已经读到了这个 messageId 及其之前的所有消息”。
     * 作用 2：当服务端把消息转发给接收方时，把雪花算法生成的真实 ID 塞进这里发过去，让接收方也能拿到强时序游标。
     */
    private Long messageId;

    /**
     * 会话级递增序号
     * 服务端生成后回填，客户端用于增量同步（拉取 seq > lastSeq 的消息）
     */
    private Long seq;
    private Integer deliveryRevision;
}
