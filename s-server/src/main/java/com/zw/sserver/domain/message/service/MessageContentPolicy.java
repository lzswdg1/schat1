package com.zw.sserver.domain.message.service;

public final class MessageContentPolicy {

    private MessageContentPolicy() {
    }

    public static void validate(String clientMsgId, String content, String quoteContent) {
        if (clientMsgId == null || clientMsgId.isBlank()) {
            throw new IllegalArgumentException("缺少 clientMsgId");
        }
        if (clientMsgId.length() > 64) {
            throw new IllegalArgumentException("clientMsgId 不能超过 64 个字符");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        if (content.length() > 5000) {
            throw new IllegalArgumentException("消息内容不能超过 5000 个字符");
        }
        if (quoteContent != null && quoteContent.length() > 255) {
            throw new IllegalArgumentException("引用摘要不能超过 255 个字符");
        }
    }
}
