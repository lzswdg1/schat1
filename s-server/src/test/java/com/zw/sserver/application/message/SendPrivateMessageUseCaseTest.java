package com.zw.sserver.application.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.domain.message.model.PersistedMessageRef;
import com.zw.sserver.domain.message.repository.MessageOutboxPort;
import com.zw.sserver.domain.message.repository.PrivateMessagePersistencePort;
import com.zw.sserver.domain.message.service.ConversationSequencePort;
import com.zw.sserver.domain.message.service.PrivateMessagePolicy;
import com.zw.sserver.model.dto.ChatMsgDTO;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SendPrivateMessageUseCaseTest {

    private final ConversationSequencePort sequence = mock(ConversationSequencePort.class);
    private final PrivateMessagePersistencePort persistence = mock(PrivateMessagePersistencePort.class);
    private final MessageOutboxPort outbox = mock(MessageOutboxPort.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final MessageSendAdmission admission = mock(MessageSendAdmission.class);
    private final SendPrivateMessageUseCase useCase = new SendPrivateMessageUseCase(
            new ObjectMapper(), new PrivateMessagePolicy(), sequence, outbox, persistence, transactions, admission);

    private ChatMsgDTO message() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(sequence.nextPrivateSeq(1L, 2L)).thenReturn(101L);
        ChatMsgDTO message = new ChatMsgDTO();
        message.setToId(2L);
        message.setClientMsgId("private-client");
        message.setMsgType("TEXT");
        message.setContent("hello");
        return message;
    }

    @Test
    void successRequiresMessageAndOutboxCommit() {
        ChatMsgDTO message = message();
        SendMessageResult result = useCase.execute(new SendPrivateMessageCommand(1L, message));

        assertTrue(result.isSuccess());
        assertEquals(101L, result.getSeq());
        var order = inOrder(sequence, persistence, outbox, transactions);
        order.verify(sequence).nextPrivateSeq(1L, 2L);
        order.verify(persistence).saveImmediately(argThat(saved -> saved.getSeq() == 101L));
        order.verify(outbox).enqueue(eq(result.getMessageId()), eq(2L), contains("NEW_MESSAGE"));
        order.verify(outbox).enqueue(result.getMessageId(), 1L, null);
        order.verify(transactions).commit(any());
    }

    @Test
    void outboxFailureRollsBackAndNeverReturnsSuccess() {
        ChatMsgDTO message = message();
        doThrow(new IllegalStateException("disk full")).when(outbox).enqueue(anyLong(), anyLong(), any());
        assertFalse(useCase.execute(new SendPrivateMessageCommand(1L, message)).isSuccess());
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    @Test
    void failedCommitNeverReturnsSuccess() {
        ChatMsgDTO message = message();
        doThrow(new IllegalStateException("commit failed")).when(transactions).commit(any());
        assertFalse(useCase.execute(new SendPrivateMessageCommand(1L, message)).isSuccess());
    }

    @Test
    void duplicateRollsBackThenReturnsOriginalIdentityWithoutAnotherEvent() {
        ChatMsgDTO message = message();
        doThrow(new DuplicateKeyException("duplicate")).when(persistence).saveImmediately(any());
        when(persistence.findByClientMsgId(1L, "private-client"))
                .thenReturn(Optional.of(new PersistedMessageRef(88L, 77L)));
        SendMessageResult result = useCase.execute(new SendPrivateMessageCommand(1L, message));
        assertEquals("duplicate", result.getStatus());
        assertEquals(88L, result.getMessageId());
        assertEquals(77L, result.getSeq());
        var order = inOrder(transactions, persistence);
        order.verify(transactions).rollback(any());
        order.verify(persistence).findByClientMsgId(1L, "private-client");
        verifyNoInteractions(outbox);
    }

    @Test
    void invalidRecipientDoesNotStartTransaction() {
        ChatMsgDTO message = message();
        message.setToId(1L);
        assertFalse(useCase.execute(new SendPrivateMessageCommand(1L, message)).isSuccess());
        verifyNoInteractions(persistence, outbox, transactions);
    }

    @Test
    void excessiveContentIsRejectedBeforeAdmissionOrPersistence() {
        ChatMsgDTO message = message();
        message.setContent("x".repeat(5001));
        assertFalse(useCase.execute(new SendPrivateMessageCommand(1L, message)).isSuccess());
        verifyNoInteractions(admission, persistence, outbox, transactions);
    }

    @Test
    void exhaustedBudgetDoesNotStartTransaction() {
        ChatMsgDTO message = message();
        doThrow(new IllegalArgumentException("发送过于频繁")).when(admission).check(1L);
        assertFalse(useCase.execute(new SendPrivateMessageCommand(1L, message)).isSuccess());
        verifyNoInteractions(sequence, persistence, outbox, transactions);
    }

    @Test
    void unavailableRateLimitStoreFailsClosed() {
        ChatMsgDTO message = message();
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline")).when(admission).check(1L);
        assertFalse(useCase.execute(new SendPrivateMessageCommand(1L, message)).isSuccess());
        verifyNoInteractions(sequence, persistence, outbox, transactions);
    }
}
