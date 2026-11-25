package com.zw.sserver.domain.message.model;

/**
 * 消息投递状态。
 */
public enum MessageStatus {

    UNREAD(0),
    DELIVERED(1),
    READ(2);

    private final int code;

    MessageStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
