package com.zw.sserver.model.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RouteMessage 属性测试
 * 使用 jqwik 框架，每个属性测试至少运行 100 次迭代
 */
class RouteMessagePropertyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ========== Generators ==========

    @Provide
    Arbitrary<String> messageTypes() {
        return Arbitraries.of("CHAT", "READ", "NOTICE", "KICK_SESSION");
    }

    @Provide
    Arbitrary<Long> targetUserIds() {
        return Arbitraries.longs().between(1L, Long.MAX_VALUE);
    }

    @Provide
    Arbitrary<String> payloads() {
        // Generate JSON-like strings as payload
        return Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20),
                Arbitraries.strings().ofMinLength(0).ofMaxLength(100)
        ).as((key, value) -> "{\"" + key + "\":\"" + escapeJson(value) + "\"}");
    }

    @Provide
    Arbitrary<String> sourceInstanceIds() {
        // hostname:port format
        Arbitrary<String> hostnamePort = Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(15),
                Arbitraries.integers().between(1024, 65535)
        ).as((host, port) -> host + ":" + port);

        // IP:port format
        Arbitrary<String> ipPort = Combinators.combine(
                Arbitraries.integers().between(1, 254),
                Arbitraries.integers().between(0, 254),
                Arbitraries.integers().between(0, 254),
                Arbitraries.integers().between(1, 254),
                Arbitraries.integers().between(1024, 65535)
        ).as((a, b, c, d, port) -> a + "." + b + "." + c + "." + d + ":" + port);

        return Arbitraries.oneOf(hostnamePort, ipPort);
    }

    // ========== Helper ==========

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    private RouteMessage buildMessage(String type, Long targetUserId, String payload, String sourceInstanceId) {
        RouteMessage msg = new RouteMessage();
        msg.setType(type);
        msg.setTargetUserId(targetUserId);
        msg.setPayload(payload);
        msg.setSourceInstanceId(sourceInstanceId);
        return msg;
    }

    // ========== Property Tests ==========

    // Feature: redis-message-routing, Property 18: RouteMessage 序列化往返一致性
    // **Validates: Requirements 8.3**
    @Property(tries = 100)
    void serializeDeserializeRoundTrip(
            @ForAll("messageTypes") String type,
            @ForAll("targetUserIds") Long targetUserId,
            @ForAll("payloads") String payload,
            @ForAll("sourceInstanceIds") String sourceInstanceId) throws Exception {

        // Arrange: build a valid RouteMessage
        RouteMessage original = buildMessage(type, targetUserId, payload, sourceInstanceId);

        // Act: serialize to JSON, then deserialize back
        String json = objectMapper.writeValueAsString(original);
        RouteMessage deserialized = objectMapper.readValue(json, RouteMessage.class);

        // Assert: all fields are equal (round-trip consistency)
        assertEquals(original.getType(), deserialized.getType(),
                "type should survive serialization round-trip");
        assertEquals(original.getTargetUserId(), deserialized.getTargetUserId(),
                "targetUserId should survive serialization round-trip");
        assertEquals(original.getPayload(), deserialized.getPayload(),
                "payload should survive serialization round-trip");
        assertEquals(original.getSourceInstanceId(), deserialized.getSourceInstanceId(),
                "sourceInstanceId should survive serialization round-trip");

        // Also verify object-level equality (Lombok @Data generates equals/hashCode)
        assertEquals(original, deserialized,
                "Deserialized RouteMessage should be equal to the original");
    }
}
