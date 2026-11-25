package com.zw.sserver.application.message;

/**
 * 私聊发送结果。
 */
public class SendMessageResult {

    private final boolean success;
    private final String clientMsgId;
    private final Long messageId;
    private final Long seq;
    private final String status;
    private final String errorMessage;

    private SendMessageResult(boolean success,
                              String clientMsgId,
                              Long messageId,
                              Long seq,
                              String status,
                              String errorMessage) {
        this.success = success;
        this.clientMsgId = clientMsgId;
        this.messageId = messageId;
        this.seq = seq;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public static SendMessageResult success(String clientMsgId, Long messageId, Long seq) {
        return new SendMessageResult(true, clientMsgId, messageId, seq, "success", null);
    }

    public static SendMessageResult duplicate(String clientMsgId, Long messageId, Long seq) {
        return new SendMessageResult(true, clientMsgId, messageId, seq, "duplicate", null);
    }

    public static SendMessageResult unknownDuplicate(String clientMsgId) {
        return new SendMessageResult(false, clientMsgId, 0L, null, "failed", "消息幂等状态异常，请稍后重试");
    }

    public static SendMessageResult failed(String errorMessage) {
        return new SendMessageResult(false, null, 0L, null, "failed", errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getClientMsgId() {
        return clientMsgId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public Long getSeq() {
        return seq;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
