package com.zw.sserver.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zw.sserver.model.entity.ChatMessage;
import com.zw.sserver.service.ChatMessageService;
import com.zw.sserver.mapper.ChatMessageMapper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
* @description 针对表【tb_chat_message(离线加密消息表)】的数据库操作Service实现
* @createDate 2026-02-26 20:36:31
*/
@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage>
    implements ChatMessageService{

    public List<ChatMessage> getHistoryByCursor(Long myId, Long friendId, Long cursor, Integer limit) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();

        // 1. 锁定聊天双方：(我发给朋友 OR 朋友发给我)
        wrapper.and(w -> w
                .eq(ChatMessage::getSenderId, myId).eq(ChatMessage::getReceiverId, friendId)
                .or()
                .eq(ChatMessage::getSenderId, friendId).eq(ChatMessage::getReceiverId, myId)
        );

        // 2. 游标切入点：如果 cursor 不为空，查出比这个 messageId 更小（更老）的消息
        if (cursor != null) {
            wrapper.lt(ChatMessage::getMessageId, cursor);
        }

        // 3. 按 ID 倒序（从新到老拿数据），并用安全的 LIMIT 截断
        wrapper.orderByDesc(ChatMessage::getMessageId);

        // 无论前端传什么，强制限制在 1 到 50 之间，防止恶意刷库！
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        wrapper.last("LIMIT " + safeLimit);

        List<ChatMessage> messageList = this.list(wrapper);

        // 4. 数据库查出来是从新到老的（倒序），但前端展示需要从上往下读（正序）。
        // 在后端把它反转回来，减轻前端渲染压力！
        Collections.reverse(messageList);

        return messageList;
    }
}




