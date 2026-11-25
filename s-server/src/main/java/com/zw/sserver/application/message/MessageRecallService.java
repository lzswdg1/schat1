package com.zw.sserver.application.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.infrastructure.message.JdbcMessageOutbox;
import com.zw.sserver.mapper.ChatMessageMapper;
import com.zw.sserver.mapper.GroupInfoMapper;
import com.zw.sserver.mapper.GroupMemberMapper;
import com.zw.sserver.mapper.GroupMessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MessageRecallService {

    private final ChatMessageMapper privateMessages;
    private final GroupMessageMapper groupMessages;
    private final GroupInfoMapper groups;
    private final GroupMemberMapper members;
    private final JdbcMessageOutbox outbox;
    private final ObjectMapper mapper;

    public MessageRecallService(ChatMessageMapper privateMessages, GroupMessageMapper groupMessages,
                                GroupInfoMapper groups, GroupMemberMapper members,
                                JdbcMessageOutbox outbox, ObjectMapper mapper) {
        this.privateMessages = privateMessages;
        this.groupMessages = groupMessages;
        this.groups = groups;
        this.members = members;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public Result recall(long userId, long messageId) {
        if (privateMessages.selectById(messageId) != null) {
            var message = privateMessages.selectForUpdate(messageId);
            if (message == null) throw new IllegalArgumentException("消息不存在");
            validateOwner(userId, message.getSenderId());
            List<Long> recipients = List.of(message.getReceiverId());
            if (!isRecalled(message.getCipherContent())) {
                validateAge(message.getSendTime());
                message.setCipherContent(recalledContent(userId));
                privateMessages.updateById(message);
                outbox.replaceWithRecall(messageId, recipients,
                        envelope(messageId, userId, String.valueOf(userId), message.getSeq(), message.getSendTime()));
            }
            return new Result(userId, message.getReceiverId(), null, recipients);
        }
        var observed = groupMessages.selectById(messageId);
        if (observed == null || groups.selectForUpdate(observed.getGroupId()) == null) {
            throw new IllegalArgumentException("消息不存在");
        }
        var message = groupMessages.selectForUpdate(messageId);
        if (message == null) throw new IllegalArgumentException("消息不存在");
        validateOwner(userId, message.getSenderId());
        List<Long> recipients = members.selectMemberIds(message.getGroupId());
        if (!isRecalled(message.getCipherContent())) {
            validateAge(message.getSendTime());
            message.setCipherContent(recalledContent(userId));
            groupMessages.updateById(message);
            outbox.replaceWithRecall(messageId, recipients.stream().filter(memberId -> memberId != userId).toList(),
                    envelope(messageId, userId, "group_" + message.getGroupId(), message.getSeq(), message.getSendTime()));
        }
        return new Result(userId, null, message.getGroupId(), recipients);
    }

    private void validateOwner(long userId, Long senderId) {
        if (!Long.valueOf(userId).equals(senderId)) throw new IllegalArgumentException("只能撤回自己发送的消息");
    }

    private void validateAge(java.util.Date sentAt) {
        if (System.currentTimeMillis() - sentAt.getTime() > 120000) {
            throw new IllegalArgumentException("超过 2 分钟，无法撤回");
        }
    }

    private boolean isRecalled(String payload) {
        return payload != null && payload.contains("\"recalled\":true");
    }

    private String recalledContent(long userId) {
        return json(Map.of("msgType", "RECALL", "operatorId", String.valueOf(userId), "recalled", true));
    }

    private String envelope(long messageId, long senderId, String sessionId, Long seq, java.util.Date sentAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(messageId));
        data.put("senderId", String.valueOf(senderId));
        data.put("sessionId", sessionId);
        data.put("seq", String.valueOf(seq));
        data.put("timestamp", sentAt.getTime());
        data.put("type", "text");
        data.put("content", "");
        data.put("quote", null);
        data.put("fileMeta", null);
        data.put("recalled", true);
        return json(Map.of("type", "NEW_MESSAGE", "data", data));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("撤回消息序列化失败", exception);
        }
    }

    public record Result(long senderId, Long receiverId, Long groupId, List<Long> recipients) {
    }
}
