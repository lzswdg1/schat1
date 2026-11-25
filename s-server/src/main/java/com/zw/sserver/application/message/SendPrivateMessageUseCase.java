package com.zw.sserver.application.message;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.domain.message.repository.MessageOutboxPort;
import com.zw.sserver.domain.message.model.PrivateMessage;
import com.zw.sserver.domain.message.repository.PrivateMessagePersistencePort;
import com.zw.sserver.domain.message.service.ConversationSequencePort;
import com.zw.sserver.domain.message.service.PrivateMessagePolicy;
import com.zw.sserver.model.dto.ChatMsgDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发送私聊消息用例。
 */
@Service
public class SendPrivateMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendPrivateMessageUseCase.class);

    private final ObjectMapper objectMapper;
    private final PrivateMessagePolicy privateMessagePolicy;
    private final ConversationSequencePort conversationSequencePort;
    private final MessageOutboxPort outbox;
    private final TransactionTemplate transactions;
    private final PrivateMessagePersistencePort privateMessagePersistencePort;
    private final MessageSendAdmission admission;

    public SendPrivateMessageUseCase(ObjectMapper objectMapper,
                                     PrivateMessagePolicy privateMessagePolicy,
                                     ConversationSequencePort conversationSequencePort,
                                     MessageOutboxPort outbox,
                                     PrivateMessagePersistencePort privateMessagePersistencePort,
                                     PlatformTransactionManager transactionManager,
                                     MessageSendAdmission admission) {
        this.objectMapper = objectMapper;
        this.privateMessagePolicy = privateMessagePolicy;
        this.conversationSequencePort = conversationSequencePort;
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.privateMessagePersistencePort = privateMessagePersistencePort;
        this.admission = admission;
    }

    public SendMessageResult execute(SendPrivateMessageCommand command) {
        ChatMsgDTO msgDTO = command.getMessage();
        try {
            privateMessagePolicy.validate(command.getAuthedUid(), msgDTO);
            admission.check(command.getAuthedUid());

            return transactions.execute(status -> persist(command));
        } catch (DuplicateKeyException e) {
            return privateMessagePersistencePort
                    .findByClientMsgId(command.getAuthedUid(), msgDTO.getClientMsgId())
                    .map(existing -> SendMessageResult.duplicate(
                            msgDTO.getClientMsgId(), existing.messageId(), existing.seq()))
                    .orElseGet(() -> SendMessageResult.unknownDuplicate(msgDTO.getClientMsgId()));
        } catch (IllegalArgumentException e) {
            return SendMessageResult.failed(e.getMessage());
        } catch (Exception e) {
            log.error("私聊消息发送失败", e);
            return SendMessageResult.failed("服务器处理失败");
        }
    }

    private SendMessageResult persist(SendPrivateMessageCommand command) {
        ChatMsgDTO msgDTO = command.getMessage();
        try {
            long messageId = IdWorker.getId();
            long seq = conversationSequencePort.nextPrivateSeq(command.getAuthedUid(), msgDTO.getToId());
            msgDTO.setMessageId(messageId);
            msgDTO.setSeq(seq);

            String finalMessageJson = objectMapper.writeValueAsString(msgDTO);
            PrivateMessage message = PrivateMessage.create(
                    messageId,
                    msgDTO.getClientMsgId(),
                    command.getAuthedUid(),
                    msgDTO.getToId(),
                    finalMessageJson,
                    msgDTO.getQuoteMsgId(),
                    msgDTO.getQuoteContent(),
                    seq
            );

            privateMessagePersistencePort.saveImmediately(message);
            outbox.enqueue(messageId, msgDTO.getToId(), wrapNewMessage(command.getAuthedUid(), msgDTO, messageId));
            outbox.enqueue(messageId, command.getAuthedUid(), null);
            return SendMessageResult.success(msgDTO.getClientMsgId(), messageId, seq);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("消息序列化失败", exception);
        }
    }

    private String wrapNewMessage(Long senderId, ChatMsgDTO msgDTO, long messageId) throws JsonProcessingException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(messageId));
        data.put("sessionId", String.valueOf(senderId));
        data.put("type", msgDTO.getMsgType() != null ? msgDTO.getMsgType().toLowerCase() : "text");
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
