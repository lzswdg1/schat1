package com.zw.sserver.domain.message.service;

import com.zw.sserver.model.dto.ChatMsgDTO;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 私聊消息领域规则。
 */
@Component
public class PrivateMessagePolicy {

    private static final Set<String> ALLOWED_TYPES = Set.of("TEXT", "IMAGE", "FILE");

    public void validate(Long authedUid, ChatMsgDTO msgDTO) {
        if (authedUid == null || authedUid <= 0) {
            throw new IllegalArgumentException("非法请求：缺少登录用户");
        }
        MessageContentPolicy.validate(msgDTO.getClientMsgId(), msgDTO.getContent(), msgDTO.getQuoteContent());
        if (msgDTO.getMsgType() == null || !ALLOWED_TYPES.contains(msgDTO.getMsgType().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("不支持的私聊消息类型");
        }
        if (msgDTO.getToId() == null || msgDTO.getToId() <= 0) {
            throw new IllegalArgumentException("非法请求：缺少 toId");
        }
        if (authedUid.equals(msgDTO.getToId())) {
            throw new IllegalArgumentException("不能给自己发送私聊消息");
        }
    }
}
