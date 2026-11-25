package com.zw.sserver.application.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.domain.message.model.PersistedMessageRef;
import com.zw.sserver.domain.message.repository.GroupMessagePersistencePort;
import com.zw.sserver.domain.message.repository.MessageOutboxPort;
import com.zw.sserver.domain.message.service.ConversationSequencePort;
import com.zw.sserver.domain.message.service.GroupMembershipPort;
import com.zw.sserver.domain.message.service.GroupMessagePolicy;
import com.zw.sserver.model.dto.GroupMsgDTO;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SendGroupMessageUseCaseTest {

    private final ConversationSequencePort sequence = mock(ConversationSequencePort.class);
    private final GroupMembershipPort membership = mock(GroupMembershipPort.class);
    private final GroupMessagePersistencePort persistence = mock(GroupMessagePersistencePort.class);
    private final MessageOutboxPort outbox = mock(MessageOutboxPort.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final MessageSendAdmission admission = mock(MessageSendAdmission.class);
    private final SendGroupMessageUseCase useCase = new SendGroupMessageUseCase(
            new ObjectMapper(), new GroupMessagePolicy(), membership, sequence, outbox, persistence, transactions, admission);

    private GroupMsgDTO message() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(sequence.nextGroupSeq(10L)).thenReturn(202L);
        when(membership.isMember(10L, 1L)).thenReturn(true);
        when(membership.memberIds(10L)).thenReturn(Set.of(1L, 2L, 3L));
        when(membership.lockAndLoadMembers(10L, 1L)).thenReturn(Set.of(1L, 2L, 3L));
        when(persistence.findByClientMsgId(anyLong(), anyLong(), anyString())).thenReturn(Optional.empty());
        GroupMsgDTO message = new GroupMsgDTO();
        message.setGroupId(10L);
        message.setClientMsgId("group-client");
        message.setMsgType("GROUP_TEXT");
        message.setContent("hello");
        return message;
    }

    @Test
    void groupCreatesIndependentRecipientEventsBeforeCommit() {
        SendMessageResult result = useCase.execute(new SendGroupMessageCommand(1L, message()));
        assertTrue(result.isSuccess());
        assertEquals(202L, result.getSeq());
        verify(outbox).enqueue(result.getMessageId(), 1L, null);
        verify(outbox).enqueue(eq(result.getMessageId()), eq(2L), contains("NEW_MESSAGE"));
        verify(outbox).enqueue(eq(result.getMessageId()), eq(3L), contains("group_10"));
        var order = inOrder(persistence, outbox, transactions);
        order.verify(persistence).saveImmediately(any());
        order.verify(outbox, times(3)).enqueue(anyLong(), anyLong(), any());
        order.verify(transactions).commit(any());
    }

    @Test
    void partialRecipientEnqueueFailureRollsBackWholeSend() {
        GroupMsgDTO message = message();
        doThrow(new IllegalStateException("outbox failed")).when(outbox).enqueue(anyLong(), eq(3L), any());
        assertFalse(useCase.execute(new SendGroupMessageCommand(1L, message)).isSuccess());
        verify(transactions).rollback(any());
    }

    @Test
    void existingMessageDoesNotAllocateAnotherSequence() {
        GroupMsgDTO message = message();
        when(persistence.findByClientMsgId(10L, 1L, "group-client"))
                .thenReturn(Optional.of(new PersistedMessageRef(88L, 66L)));
        SendMessageResult result = useCase.execute(new SendGroupMessageCommand(1L, message));
        assertEquals("duplicate", result.getStatus());
        assertEquals(88L, result.getMessageId());
        verifyNoInteractions(sequence, outbox, transactions);
    }

    @Test
    void concurrentDuplicateRollsBackBeforeLookup() {
        GroupMsgDTO message = message();
        when(persistence.findByClientMsgId(10L, 1L, "group-client")).thenReturn(
                Optional.empty(), Optional.of(new PersistedMessageRef(88L, 66L)));
        doThrow(new DuplicateKeyException("duplicate")).when(persistence).saveImmediately(any());
        SendMessageResult result = useCase.execute(new SendGroupMessageCommand(1L, message));
        assertEquals("duplicate", result.getStatus());
        verify(transactions).rollback(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void invalidMessageTypeDoesNotStartTransaction() {
        GroupMsgDTO message = message();
        message.setMsgType("GROUP_SCRIPT");
        assertFalse(useCase.execute(new SendGroupMessageCommand(1L, message)).isSuccess());
        verifyNoInteractions(persistence, outbox, transactions);
    }

    @Test
    void exhaustedBudgetRejectsBeforeMembershipAndDatabaseWork() {
        GroupMsgDTO message = message();
        doThrow(new IllegalArgumentException("发送过于频繁")).when(admission).check(1L);
        assertFalse(useCase.execute(new SendGroupMessageCommand(1L, message)).isSuccess());
        verifyNoInteractions(sequence, persistence, outbox, transactions);
        verify(membership, never()).isMember(anyLong(), anyLong());
    }

    @Test
    void unavailableRateLimitStoreFailsClosed() {
        GroupMsgDTO message = message();
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline")).when(admission).check(1L);
        assertFalse(useCase.execute(new SendGroupMessageCommand(1L, message)).isSuccess());
        verifyNoInteractions(sequence, persistence, outbox, transactions);
    }
}
