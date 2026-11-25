package com.zw.sserver.websocket.handler;

import com.zw.sserver.application.message.DeliveryAcknowledgementService;
import com.zw.sserver.model.dto.ChatMsgDTO;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class DeliveryAckHandlerTest {

    @Test
    void acknowledgementAlwaysUsesAuthenticatedRecipient() {
        DeliveryAcknowledgementService acknowledgements = mock(DeliveryAcknowledgementService.class);
        when(acknowledgements.acknowledge(123L, 2L, 0)).thenReturn(java.util.Optional.empty());
        ChatMsgDTO message = new ChatMsgDTO();
        message.setMessageId(123L);
        message.setFromId(999L);
        message.setToId(888L);
        new DeliveryAckHandler(acknowledgements, mock(com.zw.sserver.router.CrossNodeRouter.class),
                mock(com.zw.sserver.registry.InstanceRegistry.class)).handle(null, 2L, message, "{}");
        verify(acknowledgements).acknowledge(123L, 2L, 0);
    }

    @Test
    void malformedAcknowledgementDoesNotModifyDeliveryState() {
        DeliveryAcknowledgementService acknowledgements = mock(DeliveryAcknowledgementService.class);
        new DeliveryAckHandler(acknowledgements, mock(com.zw.sserver.router.CrossNodeRouter.class),
                mock(com.zw.sserver.registry.InstanceRegistry.class)).handle(null, 2L, new ChatMsgDTO(), "{}");
        verifyNoInteractions(acknowledgements);
    }
}
