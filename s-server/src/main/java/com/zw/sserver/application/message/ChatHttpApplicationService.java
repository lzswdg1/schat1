package com.zw.sserver.application.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.scommon.api.CommonResult;
import com.zw.sserver.application.session.SessionCacheInvalidationService;
import com.zw.sserver.mapper.ChatMessageMapper;
import com.zw.sserver.mapper.GroupMemberMapper;
import com.zw.sserver.mapper.GroupMessageMapper;
import com.zw.sserver.model.dto.ChatMsgDTO;
import com.zw.sserver.model.dto.GroupMsgDTO;
import com.zw.sserver.model.dto.RouteMessage;
import com.zw.sserver.model.entity.ChatMessage;
import com.zw.sserver.model.entity.GroupMessage;
import com.zw.sserver.model.vo.ChatHistoryVO;
import com.zw.sserver.model.vo.SessionVO;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.router.CrossNodeRouter;
import com.zw.sserver.service.GroupMemberCacheService;
import com.zw.sserver.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 聊天 HTTP 用例编排，保持 Controller 只负责协议适配。
 */
@Service
public class ChatHttpApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ChatHttpApplicationService.class);

    private final ChatMessageMapper chatMessageMapper;
    private final CrossNodeRouter crossNodeRouter;
    private final InstanceRegistry instanceRegistry;
    private final ObjectMapper objectMapper;
    private final GroupMessageMapper groupMessageMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupMemberCacheService groupMemberCacheService;
    private final SessionService sessionService;
    private final SessionCacheInvalidationService sessionCacheInvalidationService;
    private final SendPrivateMessageUseCase sendPrivateMessageUseCase;
    private final SendGroupMessageUseCase sendGroupMessageUseCase;
    private final MessageRecallService messageRecallService;

    public ChatHttpApplicationService(ChatMessageMapper chatMessageMapper,
                                      CrossNodeRouter crossNodeRouter,
                                      InstanceRegistry instanceRegistry,
                                      ObjectMapper objectMapper,
                                      GroupMessageMapper groupMessageMapper,
                                      GroupMemberMapper groupMemberMapper,
                                      GroupMemberCacheService groupMemberCacheService,
                                      SessionService sessionService,
                                      SessionCacheInvalidationService sessionCacheInvalidationService,
                                      SendPrivateMessageUseCase sendPrivateMessageUseCase,
                                      SendGroupMessageUseCase sendGroupMessageUseCase, MessageRecallService messageRecallService) {
        this.messageRecallService = messageRecallService;
        this.chatMessageMapper = chatMessageMapper;
        this.crossNodeRouter = crossNodeRouter;
        this.instanceRegistry = instanceRegistry;
        this.objectMapper = objectMapper;
        this.groupMessageMapper = groupMessageMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.groupMemberCacheService = groupMemberCacheService;
        this.sessionService = sessionService;
        this.sessionCacheInvalidationService = sessionCacheInvalidationService;
        this.sendPrivateMessageUseCase = sendPrivateMessageUseCase;
        this.sendGroupMessageUseCase = sendGroupMessageUseCase;
    }

    public CommonResult<List<SessionVO>> getSessions(Long userId) {
        return CommonResult.success(sessionService.getSessionList(userId));
    }

    public CommonResult<Map<String, Object>> getHistory(Long userId, String sessionId, Long cursor, Integer size) {
        int safeLimit = Math.min(Math.max(size, 1), 50);

        if (sessionId.startsWith("group_")) {
            Long groupId = parseGroupSessionId(sessionId);
            if (groupId == null) {
                return CommonResult.failed("无效的群聊 sessionId");
            }
            if (!groupMemberCacheService.isMember(groupId, userId)) {
                return CommonResult.failed("你不是该群成员");
            }
            LambdaQueryWrapper<GroupMessage> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GroupMessage::getGroupId, groupId);
            if (cursor != null) wrapper.lt(GroupMessage::getMessageId, cursor);
            wrapper.orderByDesc(GroupMessage::getMessageId);
            wrapper.last("LIMIT " + safeLimit);
            List<GroupMessage> messages = groupMessageMapper.selectList(wrapper);
            Collections.reverse(messages);

            List<Map<String, Object>> voList = messages.stream().map(msg ->
                    toFrontendMessage(msg.getMessageId(), msg.getSenderId(),
                            "group_" + groupId, msg.getCipherContent(), msg.getSendTime(),
                            msg.getQuoteMsgId(), msg.getQuoteContent(), msg.getSeq(), null)
            ).collect(Collectors.toList());

            Map<String, Object> result = new HashMap<>();
            result.put("messages", voList);
            result.put("cursor", messages.isEmpty() ? null : messages.get(0).getMessageId());
            result.put("hasMore", messages.size() >= safeLimit);
            return CommonResult.success(result);
        }

        Long friendId = parseLong(sessionId);
        if (friendId == null) {
            return CommonResult.failed("无效的 sessionId");
        }
        if (userId.equals(friendId)) {
            return CommonResult.success(Map.of("messages", List.of(), "cursor", "", "hasMore", false));
        }
        List<ChatHistoryVO> rawList = queryHistory(userId, friendId, cursor, safeLimit);
        List<Map<String, Object>> voList = rawList.stream().map(msg -> {
            Long otherId = msg.getSenderId().equals(userId) ? msg.getReceiverId() : msg.getSenderId();
            return toFrontendMessage(msg.getMessageId(), msg.getSenderId(),
                    String.valueOf(otherId), msg.getCipherContent(), msg.getSendTime(),
                    msg.getQuoteMsgId(), msg.getQuoteContent(), msg.getSeq(), msg.getIsRead());
        }).collect(Collectors.toList());
        Map<String, Object> result = new HashMap<>();
        result.put("messages", voList);
        result.put("cursor", rawList.isEmpty() ? null : rawList.get(0).getMessageId());
        result.put("hasMore", rawList.size() >= safeLimit);
        return CommonResult.success(result);
    }

    public CommonResult<String> markPrivateRead(Long userId, Long friendId, Map<String, Object> body) {
        if (!hasChatRelation(userId, friendId)) {
            return CommonResult.success("无需操作");
        }

        UpdateWrapper<ChatMessage> updateWrapper = new UpdateWrapper<>();
        updateWrapper.set("is_read", 2)
                .eq("sender_id", friendId)
                .eq("receiver_id", userId)
                .lt("is_read", 2);

        Long maxMessageId = null;
        if (body != null && body.containsKey("messageIds")) {
            @SuppressWarnings("unchecked")
            List<?> rawIds = (List<?>) body.get("messageIds");
            if (rawIds != null && !rawIds.isEmpty()) {
                List<Long> ids = new ArrayList<>();
                for (Object raw : rawIds) {
                    try {
                        ids.add(Long.parseLong(raw.toString()));
                    } catch (NumberFormatException ignored) {
                        // 跳过非数字 id（如 clientMsgId "cmsg_xxx"）
                    }
                }
                if (!ids.isEmpty()) {
                    updateWrapper.in("message_id", ids);
                    maxMessageId = ids.stream().max(Long::compareTo).orElse(null);
                }
            }
        }

        chatMessageMapper.update(null, updateWrapper);
        sessionCacheInvalidationService.evictPrivateConversation(userId, friendId);
        pushReadReceipt(userId, friendId, maxMessageId);
        return CommonResult.success("已读");
    }

    public CommonResult<Map<String, Object>> sendMessage(Long userId, String sessionId, Map<String, Object> body) {
        SendMessageResult result = sessionId.startsWith("group_")
                ? sendGroupMessage(userId, sessionId, body)
                : sendPrivateMessage(userId, sessionId, body);
        if (!result.isSuccess()) {
            return CommonResult.failed(result.getErrorMessage());
        }
        Map<String, Object> data = new HashMap<>();
        data.put("clientMsgId", result.getClientMsgId());
        data.put("messageId", String.valueOf(result.getMessageId()));
        data.put("seq", String.valueOf(result.getSeq()));
        data.put("status", result.getStatus());
        return CommonResult.success(data);
    }

    public CommonResult<String> recallMessage(Long userId, String sessionId, Long messageId) {
        MessageRecallService.Result recalled;
        try {
            recalled = messageRecallService.recall(userId, messageId);
        } catch (IllegalArgumentException exception) {
            return CommonResult.failed(exception.getMessage());
        }
        try {
            if (recalled.groupId() == null) {
                sessionCacheInvalidationService.evictPrivateConversation(userId, recalled.receiverId());
            } else {
                sessionCacheInvalidationService.evictGroupMembers(recalled.recipients());
            }
        } catch (Exception exception) {
            log.warn("撤回已提交，会话缓存失效失败", exception);
        }
        return CommonResult.success("撤回成功");
    }

    public CommonResult<Map<String, Object>> syncPrivateMessages(Long userId, Long friendId,
                                                                 Long lastMsgId, Long lastSeq,
                                                                 Integer limit) {
        if (userId.equals(friendId)) {
            return CommonResult.success(Map.of("messages", List.of(), "hasMore", false));
        }

        int safeLimit = Math.min(Math.max(limit, 1), 200);
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w
                .nested(n -> n.eq(ChatMessage::getSenderId, userId).eq(ChatMessage::getReceiverId, friendId))
                .or()
                .nested(n -> n.eq(ChatMessage::getSenderId, friendId).eq(ChatMessage::getReceiverId, userId))
        );
        boolean syncBySeq = lastSeq != null && lastSeq >= 0;
        if (syncBySeq) {
            wrapper.gt(ChatMessage::getSeq, lastSeq);
            wrapper.orderByAsc(ChatMessage::getSeq);
        } else {
            if (lastMsgId != null && lastMsgId > 0) wrapper.gt(ChatMessage::getMessageId, lastMsgId);
            wrapper.orderByAsc(ChatMessage::getMessageId);
        }
        wrapper.last("LIMIT " + safeLimit);

        List<ChatMessage> messages = chatMessageMapper.selectList(wrapper);
        List<Map<String, Object>> voList = messages.stream().map(msg -> {
            String sid = String.valueOf(msg.getSenderId().equals(userId) ? msg.getReceiverId() : msg.getSenderId());
            return toFrontendMessage(msg.getMessageId(), msg.getSenderId(), sid,
                    msg.getCipherContent(), msg.getSendTime(),
                    msg.getQuoteMsgId(), msg.getQuoteContent(), msg.getSeq(), msg.getIsRead());
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("messages", voList);
        result.put("hasMore", voList.size() >= safeLimit);
        result.put("nextSeq", messages.isEmpty() ? lastSeq : messages.get(messages.size() - 1).getSeq());
        result.put("nextMsgId", messages.isEmpty() ? lastMsgId : messages.get(messages.size() - 1).getMessageId());
        return CommonResult.success(result);
    }

    public CommonResult<List<Map<String, Object>>> searchMessages(Long userId, String keyword, Integer limit) {
        if (keyword == null || keyword.trim().isEmpty()) return CommonResult.success(List.of());

        int safeLimit = limit == null ? 50 : Math.min(Math.max(limit, 1), 100);
        String normalizedKeyword = keyword.trim().toLowerCase(Locale.ROOT);

        QueryWrapper<ChatMessage> chatWrapper = new QueryWrapper<>();
        chatWrapper.and(w -> w.eq("sender_id", userId).or().eq("receiver_id", userId))
                .like("cipher_content", keyword.trim())
                .orderByDesc("send_time").last("LIMIT " + safeLimit);
        List<ChatMessage> chatResults = chatMessageMapper.selectList(chatWrapper);

        List<Long> myGroupIds = groupMemberMapper.selectGroupIdsByUserId(userId);
        List<GroupMessage> groupResults = List.of();
        if (myGroupIds != null && !myGroupIds.isEmpty()) {
            QueryWrapper<GroupMessage> groupWrapper = new QueryWrapper<>();
            groupWrapper.in("group_id", myGroupIds)
                    .like("cipher_content", keyword.trim())
                    .orderByDesc("send_time").last("LIMIT " + safeLimit);
            groupResults = groupMessageMapper.selectList(groupWrapper);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (ChatMessage msg : chatResults) {
            Long otherId = msg.getSenderId().equals(userId) ? msg.getReceiverId() : msg.getSenderId();
            var item = toSearchResult(msg.getMessageId(), msg.getSenderId(), String.valueOf(otherId),
                    msg.getCipherContent(), msg.getSendTime(), normalizedKeyword);
            if (item != null) results.add(item);
        }
        for (GroupMessage msg : groupResults) {
            var item = toSearchResult(msg.getMessageId(), msg.getSenderId(), "group_" + msg.getGroupId(),
                    msg.getCipherContent(), msg.getSendTime(), normalizedKeyword);
            if (item != null) results.add(item);
        }

        results.sort((first, second) -> Long.compare((Long) second.get("createTime"), (Long) first.get("createTime")));
        if (results.size() > safeLimit) results = new ArrayList<>(results.subList(0, safeLimit));

        return CommonResult.success(results);
    }

    private Map<String, Object> toSearchResult(Long messageId, Long senderId, String sessionId,
                                               String payload, Date sendTime, String keyword) {
        String content = payload == null ? "" : payload;
        try {
            var node = objectMapper.readTree(content);
            if (node != null && node.isObject()) {
                String type = node.path("msgType").asText("TEXT").toUpperCase(Locale.ROOT);
                if (node.path("recalled").asBoolean(false) || !("TEXT".equals(type) || "GROUP_TEXT".equals(type))) {
                    return null;
                }
                content = node.path("content").asText("");
            } else if (node != null && node.isTextual()) {
                content = node.asText();
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            content = payload == null ? "" : payload;
        }
        if (!content.toLowerCase(Locale.ROOT).contains(keyword)) return null;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sessionId", sessionId);
        item.put("messageId", String.valueOf(messageId));
        item.put("fromId", String.valueOf(senderId));
        item.put("content", content);
        item.put("createTime", sendTime == null ? 0L : sendTime.getTime());
        return item;
    }

    private SendMessageResult sendPrivateMessage(Long userId, String sessionId, Map<String, Object> body) {
        Long toId = parseLong(sessionId);
        if (toId == null) {
            return SendMessageResult.failed("无效的 sessionId");
        }
        ChatMsgDTO dto = new ChatMsgDTO();
        dto.setClientMsgId(stringValue(body.get("clientMsgId")));
        dto.setFromId(userId);
        dto.setToId(toId);
        dto.setMsgType(normalizePrivateMsgType(stringValue(body.get("type"))));
        dto.setContent(stringValue(body.get("content")));
        dto.setQuoteMsgId(extractQuoteMsgId(body));
        dto.setQuoteContent(extractQuoteContent(body));
        return sendPrivateMessageUseCase.execute(new SendPrivateMessageCommand(userId, dto));
    }

    private SendMessageResult sendGroupMessage(Long userId, String sessionId, Map<String, Object> body) {
        Long groupId = parseGroupSessionId(sessionId);
        if (groupId == null) {
            return SendMessageResult.failed("无效的群聊 sessionId");
        }
        GroupMsgDTO dto = new GroupMsgDTO();
        dto.setClientMsgId(stringValue(body.get("clientMsgId")));
        dto.setFromId(userId);
        dto.setGroupId(groupId);
        dto.setMsgType(normalizeGroupMsgType(stringValue(body.get("type"))));
        dto.setContent(stringValue(body.get("content")));
        dto.setQuoteMsgId(extractQuoteMsgId(body));
        dto.setQuoteContent(extractQuoteContent(body));
        return sendGroupMessageUseCase.execute(new SendGroupMessageCommand(userId, dto));
    }

    private String normalizePrivateMsgType(String type) {
        if (type == null || type.isBlank()) return "TEXT";
        return type.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeGroupMsgType(String type) {
        if (type == null || type.isBlank()) return "GROUP_TEXT";
        String upper = type.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("GROUP_") ? upper : "GROUP_" + upper;
    }

    private String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    private Long extractQuoteMsgId(Map<String, Object> body) {
        Object quote = body.get("quote");
        if (quote instanceof Map<?, ?> quoteMap) {
            Object id = quoteMap.get("messageId");
            if (id != null) {
                return parseLong(id.toString());
            }
        }
        Object quoteId = body.get("quoteId");
        return quoteId != null ? parseLong(quoteId.toString()) : null;
    }

    private String extractQuoteContent(Map<String, Object> body) {
        Object quote = body.get("quote");
        if (quote instanceof Map<?, ?> quoteMap) {
            Object content = quoteMap.get("content");
            return content != null ? content.toString() : null;
        }
        return null;
    }

    private List<ChatHistoryVO> queryHistory(Long userId, Long friendId, Long cursor, int limit) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w
                .nested(n -> n.eq(ChatMessage::getSenderId, userId).eq(ChatMessage::getReceiverId, friendId))
                .or()
                .nested(n -> n.eq(ChatMessage::getSenderId, friendId).eq(ChatMessage::getReceiverId, userId))
        );
        if (cursor != null) wrapper.lt(ChatMessage::getMessageId, cursor);
        wrapper.orderByDesc(ChatMessage::getMessageId);
        wrapper.last("LIMIT " + limit);

        List<ChatMessage> historyList = chatMessageMapper.selectList(wrapper);
        Collections.reverse(historyList);
        return historyList.stream().map(msg -> {
            ChatHistoryVO vo = new ChatHistoryVO();
            BeanUtils.copyProperties(msg, vo);
            return vo;
        }).collect(Collectors.toList());
    }

    private boolean hasChatRelation(Long userId1, Long userId2) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w
                .nested(n -> n.eq(ChatMessage::getSenderId, userId1).eq(ChatMessage::getReceiverId, userId2))
                .or()
                .nested(n -> n.eq(ChatMessage::getSenderId, userId2).eq(ChatMessage::getReceiverId, userId1))
        );
        wrapper.last("LIMIT 1");
        return chatMessageMapper.selectCount(wrapper) > 0;
    }

    private String buildRecalledContent(Long operatorId) {
        return String.format("{\"msgType\":\"RECALL\",\"operatorId\":%d,\"recalled\":true}", operatorId);
    }

    private Map<String, Object> toFrontendMessage(Long messageId, Long senderId, String sessionId,
                                                   String cipherContent, Date sendTime,
                                                   Long quoteMsgId, String quoteContent,
                                                   Long seq, Integer deliveryStatus) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", String.valueOf(messageId));
        vo.put("sessionId", sessionId);
        vo.put("senderId", String.valueOf(senderId));
        vo.put("timestamp", sendTime != null ? sendTime.getTime() : 0L);
        vo.put("seq", seq == null ? null : String.valueOf(seq));
        if (deliveryStatus != null) {
            vo.put("deliveryStatus", deliveryStatus);
        }

        String content = "";
        String msgType = "text";
        boolean recalled = false;
        if (cipherContent != null && !cipherContent.isEmpty()) {
            try {
                var node = objectMapper.readTree(cipherContent);
                if (node.has("content")) content = node.get("content").asText("");
                if (node.has("msgType")) msgType = node.get("msgType").asText("TEXT").toLowerCase();
                if (node.has("recalled")) recalled = node.get("recalled").asBoolean(false);
            } catch (Exception e) {
                content = cipherContent;
            }
        }
        vo.put("type", msgType);
        vo.put("content", content);
        vo.put("recalled", recalled);

        if (quoteMsgId != null) {
            Map<String, Object> quote = new HashMap<>();
            quote.put("messageId", quoteMsgId);
            quote.put("content", quoteContent != null ? quoteContent : "");
            vo.put("quote", quote);
        } else {
            vo.put("quote", null);
        }

        return vo;
    }

    private void pushReadReceipt(Long readerId, Long senderId, Long maxMessageId) {
        try {
            Map<String, Object> readData = new LinkedHashMap<>();
            readData.put("type", "READ_RECEIPT");
            readData.put("data", Map.of(
                    "sessionId", String.valueOf(readerId),
                    "readerId", String.valueOf(readerId),
                    "maxMessageId", maxMessageId != null ? maxMessageId : 0
            ));
            String payload = objectMapper.writeValueAsString(readData);

            RouteMessage routeMsg = new RouteMessage();
            routeMsg.setType("READ");
            routeMsg.setTargetUserId(senderId);
            routeMsg.setPayload(payload);
            routeMsg.setSourceInstanceId(instanceRegistry.getInstanceId());
            crossNodeRouter.route(routeMsg);
        } catch (Exception e) {
            log.warn("已读回执推送失败: readerId={}, senderId={}", readerId, senderId, e);
        }
    }

    private void pushRecallNotification(Long targetUserId, String sessionId, Long messageId, Long operatorId) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "type", "NEW_MESSAGE",
                    "data", Map.of(
                            "id", messageId,
                            "sessionId", sessionId,
                            "type", "recall",
                            "senderId", String.valueOf(operatorId),
                            "content", "",
                            "timestamp", System.currentTimeMillis(),
                            "recalled", true
                    )
            ));
            RouteMessage routeMsg = new RouteMessage();
            routeMsg.setType("CHAT");
            routeMsg.setTargetUserId(targetUserId);
            routeMsg.setPayload(payload);
            routeMsg.setSourceInstanceId(instanceRegistry.getInstanceId());
            crossNodeRouter.route(routeMsg);
        } catch (Exception e) {
            log.warn("撤回通知推送失败: targetUserId={}, messageId={}", targetUserId, messageId, e);
        }
    }

    private Long parseGroupSessionId(String sessionId) {
        if (sessionId == null || !sessionId.startsWith("group_")) {
            return null;
        }
        return parseLong(sessionId.substring("group_".length()));
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }
}
