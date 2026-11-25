package com.zw.sserver.websocket.handler;

import com.zw.sserver.application.message.DeliveryAcknowledgementService;
import com.zw.sserver.model.dto.ChatMsgDTO;
import com.zw.sserver.model.dto.RouteMessage;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.router.CrossNodeRouter;
import jakarta.websocket.Session;
import org.springframework.stereotype.Component;

@Component
public class DeliveryAckHandler implements MessageHandler {

    private final DeliveryAcknowledgementService acknowledgements;
    private final CrossNodeRouter router;
    private final InstanceRegistry instances;

    public DeliveryAckHandler(DeliveryAcknowledgementService acknowledgements, CrossNodeRouter router, InstanceRegistry instances) {
        this.acknowledgements = acknowledgements;
        this.router = router;
        this.instances = instances;
    }

    @Override
    public String getMsgType() {
        return "DELIVERY_ACK";
    }

    @Override
    public void handle(Session session, Long authedUid, ChatMsgDTO message, String rawJson) {
        if (authedUid != null && message.getMessageId() != null && message.getMessageId() > 0) {
            int revision = message.getDeliveryRevision() == null ? 0 : message.getDeliveryRevision();
            acknowledgements.acknowledge(message.getMessageId(), authedUid, revision).ifPresent(senderId -> {
                RouteMessage receipt = new RouteMessage();
                receipt.setType("DELIVERED");
                receipt.setTargetUserId(senderId);
                receipt.setSourceInstanceId(instances.getInstanceId());
                receipt.setPayload("{\"type\":\"DELIVERED\",\"data\":{\"messageIds\":[\"" + message.getMessageId() + "\"]}}");
                router.route(receipt);
            });
        }
    }
}
