package com.zw.sserver.infrastructure.message;

import com.zw.sserver.application.session.SessionCacheInvalidationService;
import com.zw.sserver.domain.message.service.MessageDeliveryPort;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessageOutboxPublisherTest {

    @Test
    void failureForOneRecipientDoesNotBlockOthersAndIsNotMarkedPublished() {
        JdbcMessageOutbox outbox = mock(JdbcMessageOutbox.class);
        MessageDeliveryPort delivery = mock(MessageDeliveryPort.class);
        SessionCacheInvalidationService cache = mock(SessionCacheInvalidationService.class);
        var first = new JdbcMessageOutbox.Entry(1, 2, "payload", 1, "first");
        var second = new JdbcMessageOutbox.Entry(2, 3, "payload", 1, "second");
        when(outbox.claim(100)).thenReturn(List.of(first, second));
        when(delivery.deliverPrivate(3L, "payload")).thenReturn(true);

        var envelopes = mock(DeliveryEnvelope.class);
        when(envelopes.stamp(any())).thenReturn("payload");
        new MessageOutboxPublisher(outbox, delivery, cache, envelopes, healthyRedis()).publish();

        verify(outbox).retry(eq(first), anyString());
        verify(outbox, never()).complete(first);
        verify(outbox).complete(second);
        verify(cache).evictUser(2L);
        verify(cache).evictUser(3L);
    }

    @Test
    void senderCacheEventDoesNotSendMessageBackToSender() {
        JdbcMessageOutbox outbox = mock(JdbcMessageOutbox.class);
        MessageDeliveryPort delivery = mock(MessageDeliveryPort.class);
        SessionCacheInvalidationService cache = mock(SessionCacheInvalidationService.class);
        var entry = new JdbcMessageOutbox.Entry(1, 1, null, 1, "owner");
        when(outbox.claim(100)).thenReturn(List.of(entry));
        new MessageOutboxPublisher(outbox, delivery, cache, mock(DeliveryEnvelope.class), healthyRedis()).publish();
        verify(cache).evictUser(1L);
        verify(outbox).complete(entry);
        verifyNoInteractions(delivery);
    }

    @Test
    void disconnectedNodeDoesNotClaimTasksOrConsumeDeliveryAttempts() {
        var outbox = mock(JdbcMessageOutbox.class);
        var redis = healthyRedis();
        when(redis.execute(any(RedisCallback.class))).thenThrow(new RedisConnectionFailureException("unreachable"));
        var publisher = new MessageOutboxPublisher(outbox, mock(MessageDeliveryPort.class),
                mock(SessionCacheInvalidationService.class), mock(DeliveryEnvelope.class), redis);
        publisher.publish();
        publisher.publish();
        verifyNoInteractions(outbox);
    }

    @Test
    void healedNodeResumesClaimingOnTheNextSuccessfulProbe() {
        var outbox = mock(JdbcMessageOutbox.class);
        var redis = healthyRedis();
        when(redis.execute(any(RedisCallback.class))).thenThrow(new RedisConnectionFailureException("unreachable")).thenReturn("PONG");
        when(outbox.claim(100)).thenReturn(List.of());
        var publisher = new MessageOutboxPublisher(outbox, mock(MessageDeliveryPort.class),
                mock(SessionCacheInvalidationService.class), mock(DeliveryEnvelope.class), redis);
        publisher.publish();
        publisher.publish();
        verify(outbox, times(1)).claim(100);
    }

    @Test
    void missingProbeAcknowledgementIsNotTreatedAsReadiness() {
        var outbox = mock(JdbcMessageOutbox.class);
        new MessageOutboxPublisher(outbox, mock(MessageDeliveryPort.class), mock(SessionCacheInvalidationService.class),
                mock(DeliveryEnvelope.class), mock(StringRedisTemplate.class)).publish();
        verifyNoInteractions(outbox);
    }

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate healthyRedis() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisCallback.class))).thenReturn("PONG");
        return redis;
    }
}
