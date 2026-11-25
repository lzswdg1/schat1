package com.zw.sserver.domain.message.model;

/**
 * 会话标识值对象。
 */
public class ConversationId {

    private final String value;

    private ConversationId(String value) {
        this.value = value;
    }

    public static ConversationId privateChat(Long userA, Long userB) {
        long min = Math.min(userA, userB);
        long max = Math.max(userA, userB);
        return new ConversationId("private:" + min + ":" + max);
    }

    public static ConversationId group(Long groupId) {
        return new ConversationId("group:" + groupId);
    }

    public String getValue() {
        return value;
    }
}
