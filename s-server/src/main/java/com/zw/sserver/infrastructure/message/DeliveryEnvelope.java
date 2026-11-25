package com.zw.sserver.infrastructure.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class DeliveryEnvelope {

    private final JdbcMessageOutbox outbox;
    private final ObjectMapper mapper;

    public DeliveryEnvelope(JdbcMessageOutbox outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    public String stamp(JdbcMessageOutbox.Entry entry) {
        try {
            ObjectNode envelope = (ObjectNode) mapper.readTree(entry.payload());
            envelope.put("deliveryId", String.valueOf(entry.id()));
            envelope.put("deliveryRevision", entry.revision());
            return mapper.writeValueAsString(envelope);
        } catch (Exception exception) {
            throw new IllegalArgumentException("无效投递信封", exception);
        }
    }

    public boolean maySend(long recipientId, String payload) {
        if (!payload.startsWith("{")) return true;
        try {
            var envelope = mapper.readTree(payload);
            if (!"NEW_MESSAGE".equals(envelope.path("type").asText())) return true;
            int revision = envelope.path("deliveryRevision").asInt(0);
            if (envelope.has("deliveryId")) {
                return outbox.isDeliverable(Long.parseLong(envelope.get("deliveryId").asText()), recipientId, revision,
                        Long.parseLong(envelope.path("data").path("id").asText()));
            }
            String messageId = envelope.path("data").path("id").asText();
            return !messageId.isEmpty() && outbox.isCurrentMessage(Long.parseLong(messageId), recipientId, revision);
        } catch (Exception exception) {
            return false;
        }
    }
}
