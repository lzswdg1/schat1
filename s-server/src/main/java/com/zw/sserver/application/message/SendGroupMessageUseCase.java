package com.zw.sserver.application.message;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.domain.message.repository.MessageOutboxPort;
import com.zw.sserver.domain.message.model.GroupChatMessage;
import com.zw.sserver.domain.message.repository.GroupMessagePersistencePort;
import com.zw.sserver.domain.message.service.ConversationSequencePort;
import com.zw.sserver.domain.message.service.GroupMembershipPort;
import com.zw.sserver.domain.message.service.GroupMessagePolicy;
import com.zw.sserver.model.dto.GroupMsgDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 发送群聊消息用例。
 */
@Service
public class SendGroupMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendGroupMessageUseCase.class);

    private final ObjectMapper objectMapper;
    private final GroupMessagePolicy groupMessagePolicy;
    private final GroupMembershipPort groupMembershipPort;
    private final ConversationSequencePort conversationSequencePort;
    private final MessageOutboxPort outbox;
    private final TransactionTemplate transactions;
    private final GroupMessagePersistencePort groupMessagePersistencePort;
    private final MessageSendAdmission admission;

    public SendGroupMessageUseCase(ObjectMapper objectMapper,
                                   GroupMessagePolicy groupMessagePolicy,
                                   GroupMembershipPort groupMembershipPort,
                                   ConversationSequencePort conversationSequencePort,
                                   MessageOutboxPort outbox,
                                   GroupMessagePersistencePort groupMessagePersistencePort,
                                   PlatformTransactionManager transactionManager,
                                   MessageSendAdmission admission) {
        this.objectMapper = objectMapper;
        this.groupMessagePolicy = groupMessagePolicy;
        this.groupMembershipPort = groupMembershipPort;
        this.conversationSequencePort = conversationSequencePort;
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.groupMessagePersistencePort = groupMessagePersistencePort;
        this.admission = admission;
    }

    public SendMessageResult execute(SendGroupMessageCommand command) {
        GroupMsgDTO msgDTO = command.getMessage();
        try {
            groupMessagePolicy.validate(command.getAuthedUid(), msgDTO);
            admission.check(command.getAuthedUid());

            if (!groupMembershipPort.isMember(msgDTO.getGroupId(), command.getAuthedUid())) {
                log.warn("用户 {} 不是群 {} 的成员", command.getAuthedUid(), msgDTO.getGroupId());
                return SendMessageResult.failed("你不是该群成员");
            }

            Set<Long> memberIds = groupMembershipPort.memberIds(msgDTO.getGroupId());
            if (memberIds.isEmpty()) {
                return SendMessageResult.failed("群成员列表为空");
            }

            var existingMessage = groupMessagePersistencePort.findByClientMsgId(
                    msgDTO.getGroupId(),
                    command.getAuthedUid(),
                    msgDTO.getClientMsgId()
            );
            if (existingMessage.isPresent()) {
                var existing = existingMessage.get();
                return SendMessageResult.duplicate(
                        msgDTO.getClientMsgId(), existing.messageId(), existing.seq());
            }

            return transactions.execute(status -> persist(command));
        } catch (DuplicateKeyException e) {
            return groupMessagePersistencePort
                    .findByClientMsgId(msgDTO.getGroupId(), command.getAuthedUid(), msgDTO.getClientMsgId())
                    .map(existing -> SendMessageResult.duplicate(
                            msgDTO.getClientMsgId(), existing.messageId(), existing.seq()))
                    .orElseGet(() -> SendMessageResult.unknownDuplicate(msgDTO.getClientMsgId()));
        } catch (IllegalArgumentException e) {
            return SendMessageResult.failed(e.getMessage());
        } catch (Exception e) {
            log.error("群聊消息发送失败", e);
            return SendMessageResult.failed("服务器处理失败");
        }
    }

    private SendMessageResult persist(SendGroupMessageCommand command) {
        GroupMsgDTO msgDTO = command.getMessage();
        try {
            Set<Long> memberIds = groupMembershipPort.lockAndLoadMembers(msgDTO.getGroupId(), command.getAuthedUid());
            if (memberIds.isEmpty()) throw new IllegalArgumentException("群不存在或你已不在群中");
            long messageId = IdWorker.getId();
            long seq = conversationSequencePort.nextGroupSeq(msgDTO.getGroupId());
            msgDTO.setMessageId(messageId);
            msgDTO.setFromId(command.getAuthedUid());
            msgDTO.setSeq(seq);

            String finalMessageJson = objectMapper.writeValueAsString(msgDTO);
            GroupChatMessage message = GroupChatMessage.create(
                    messageId,
                    msgDTO.getClientMsgId(),
                    msgDTO.getGroupId(),
                    command.getAuthedUid(),
                    finalMessageJson,
                    msgDTO.getQuoteMsgId(),
                    msgDTO.getQuoteContent(),
                    seq
            );

            groupMessagePersistencePort.saveImmediately(message);
            String payload = wrapGroupNewMessage(command.getAuthedUid(), msgDTO, messageId);
            for (Long memberId : memberIds) {
                outbox.enqueue(messageId, memberId, memberId.equals(command.getAuthedUid()) ? null : payload);
            }
            return SendMessageResult.success(msgDTO.getClientMsgId(), messageId, seq);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("消息序列化失败", exception);
        }
    }

    private String wrapGroupNewMessage(Long senderId, GroupMsgDTO msgDTO, long messageId) throws JsonProcessingException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(messageId));
        data.put("sessionId", "group_" + msgDTO.getGroupId());
        data.put("type", msgDTO.getMsgType() != null ? msgDTO.getMsgType().replace("GROUP_", "").toLowerCase() : "text");
        data.put("senderId", String.valueOf(senderId));
        data.put("content", msgDTO.getContent());
        data.put("timestamp", System.currentTimeMillis());
        data.put("seq", String.valueOf(msgDTO.getSeq()));
        data.put("recalled", false);
        data.put("quote", msgDTO.getQuoteMsgId() != null
                ? Map.of("messageId", msgDTO.getQuoteMsgId(), "content", msgDTO.getQuoteContent() != null ? msgDTO.getQuoteContent() : "")
                : null);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "NEW_MESSAGE");
        envelope.put("data", data);
        return objectMapper.writeValueAsString(envelope);
    }
}
