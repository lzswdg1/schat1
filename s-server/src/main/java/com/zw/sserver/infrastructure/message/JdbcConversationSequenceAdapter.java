package com.zw.sserver.infrastructure.message;

import com.zw.sserver.domain.message.service.ConversationSequencePort;
import com.zw.sserver.service.ConversationSeqService;
import org.springframework.stereotype.Component;

/**
 * 事务内会话序号适配器。
 */
@Component
public class JdbcConversationSequenceAdapter implements ConversationSequencePort {

    private final ConversationSeqService conversationSeqService;

    public JdbcConversationSequenceAdapter(ConversationSeqService conversationSeqService) {
        this.conversationSeqService = conversationSeqService;
    }

    @Override
    public long nextPrivateSeq(Long senderId, Long receiverId) {
        return conversationSeqService.nextSeq(senderId, receiverId);
    }

    @Override
    public long nextGroupSeq(Long groupId) {
        return conversationSeqService.nextGroupSeq(groupId);
    }
}
