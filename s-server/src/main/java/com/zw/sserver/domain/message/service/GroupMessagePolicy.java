package com.zw.sserver.domain.message.service;

import com.zw.sserver.model.dto.GroupMsgDTO;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.Locale;

/**
 * 群消息领域规则。
 */
@Component
public class GroupMessagePolicy {

    private static final Set<String> ALLOWED_TYPES = Set.of("GROUP_TEXT", "GROUP_IMAGE", "GROUP_FILE");

    public void validate(Long authedUid, GroupMsgDTO msgDTO) {
        if (authedUid == null || authedUid <= 0) {
            throw new IllegalArgumentException("非法请求：缺少登录用户");
        }
        if (msgDTO.getGroupId() == null || msgDTO.getGroupId() <= 0) {
            throw new IllegalArgumentException("缺少 groupId");
        }
        MessageContentPolicy.validate(msgDTO.getClientMsgId(), msgDTO.getContent(), msgDTO.getQuoteContent());
        if (msgDTO.getMsgType() == null
                || !ALLOWED_TYPES.contains(msgDTO.getMsgType().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("不支持的群消息类型");
        }
    }
}
