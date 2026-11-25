package com.zw.sserver.domain.message.model;

import java.util.Date;

/**
 * 私聊消息聚合。
 */
public class PrivateMessage {

    private final Long messageId;
    private final String clientMsgId;
    private final ConversationId conversationId;
    private final Long senderId;
    private final Long receiverId;
    private final String payload;
    private final Date sendTime;
    private final MessageStatus status;
    private final Long quoteMsgId;
    private final String quoteContent;
    private final Long seq;

    private PrivateMessage(Long messageId,
                           String clientMsgId,
                           ConversationId conversationId,
                           Long senderId,
                           Long receiverId,
                           String payload,
                           Date sendTime,
                           MessageStatus status,
                           Long quoteMsgId,
                           String quoteContent,
                           Long seq) {
        this.messageId = requireId(messageId, "消息 ID 不能为空");
        this.clientMsgId = requireText(clientMsgId, "clientMsgId 不能为空");
        this.conversationId = conversationId;
        this.senderId = requireId(senderId, "发送方不能为空");
        this.receiverId = requireId(receiverId, "接收方不能为空");
        this.payload = payload;
        this.sendTime = sendTime != null ? sendTime : new Date();
        this.status = status != null ? status : MessageStatus.UNREAD;
        this.quoteMsgId = quoteMsgId;
        this.quoteContent = quoteContent;
        this.seq = requireId(seq, "会话序号不能为空");
    }

    public static PrivateMessage create(Long messageId,
                                        String clientMsgId,
                                        Long senderId,
                                        Long receiverId,
                                        String payload,
                                        Long quoteMsgId,
                                        String quoteContent,
                                        Long seq) {
        if (senderId.equals(receiverId)) {
            throw new IllegalArgumentException("不能给自己发送私聊消息");
        }
        return new PrivateMessage(
                messageId,
                clientMsgId,
                ConversationId.privateChat(senderId, receiverId),
                senderId,
                receiverId,
                payload,
                new Date(),
                MessageStatus.UNREAD,
                quoteMsgId,
                quoteContent,
                seq
        );
    }

    private static Long requireId(Long value, String message) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    public Long getMessageId() {
        return messageId;
    }

    public String getClientMsgId() {
        return clientMsgId;
    }

    public ConversationId getConversationId() {
        return conversationId;
    }

    public Long getSenderId() {
        return senderId;
    }

    public Long getReceiverId() {
        return receiverId;
    }

    public String getPayload() {
        return payload;
    }

    public Date getSendTime() {
        return sendTime;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public Long getQuoteMsgId() {
        return quoteMsgId;
    }

    public String getQuoteContent() {
        return quoteContent;
    }

    public Long getSeq() {
        return seq;
    }
}
