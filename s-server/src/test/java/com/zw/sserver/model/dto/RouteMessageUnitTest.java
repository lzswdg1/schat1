package com.zw.sserver.model.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RouteMessage 单元测试
 * <p>
 * 测试反序列化失败时的异常处理（需求 8.4）
 */
class RouteMessageUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ========== 1. Valid JSON deserializes correctly ==========

    @Test
    @DisplayName("合法 JSON 正确反序列化为 RouteMessage")
    void validJsonDeserializesCorrectly() throws Exception {
        String json = "{\"type\":\"CHAT\",\"targetUserId\":12345,\"payload\":\"{\\\"msg\\\":\\\"hello\\\"}\",\"sourceInstanceId\":\"node-1:8082\"}";

        RouteMessage msg = objectMapper.readValue(json, RouteMessage.class);

        assertEquals("CHAT", msg.getType());
        assertEquals(12345L, msg.getTargetUserId());
        assertEquals("{\"msg\":\"hello\"}", msg.getPayload());
        assertEquals("node-1:8082", msg.getSourceInstanceId());
    }

    // ========== 2. Invalid JSON throws exception ==========

    @Test
    @DisplayName("畸形 JSON 字符串反序列化抛出 JsonProcessingException")
    void invalidJsonThrowsException() {
        String malformedJson = "{this is not valid json!!!";

        assertThrows(JsonProcessingException.class,
                () -> objectMapper.readValue(malformedJson, RouteMessage.class));
    }

    @Test
    @DisplayName("截断的 JSON 反序列化抛出 JsonProcessingException")
    void truncatedJsonThrowsException() {
        String truncatedJson = "{\"type\":\"CHAT\",\"targetUserId\":";

        assertThrows(JsonProcessingException.class,
                () -> objectMapper.readValue(truncatedJson, RouteMessage.class));
    }

    // ========== 3. Missing fields deserialize to null ==========

    @Test
    @DisplayName("部分字段缺失时，缺失字段反序列化为 null")
    void missingFieldsDeserializeToNull() throws Exception {
        String partialJson = "{\"type\":\"NOTICE\",\"targetUserId\":99}";

        RouteMessage msg = objectMapper.readValue(partialJson, RouteMessage.class);

        assertEquals("NOTICE", msg.getType());
        assertEquals(99L, msg.getTargetUserId());
        assertNull(msg.getPayload(), "缺失的 payload 应为 null");
        assertNull(msg.getSourceInstanceId(), "缺失的 sourceInstanceId 应为 null");
    }

    // ========== 4. Empty JSON object deserializes to null fields ==========

    @Test
    @DisplayName("空 JSON 对象反序列化后所有字段为 null")
    void emptyJsonObjectDeserializesToNullFields() throws Exception {
        String emptyJson = "{}";

        RouteMessage msg = objectMapper.readValue(emptyJson, RouteMessage.class);

        assertNull(msg.getType());
        assertNull(msg.getTargetUserId());
        assertNull(msg.getPayload());
        assertNull(msg.getSourceInstanceId());
    }

    // ========== 5. Null type/payload fields are handled ==========

    @Test
    @DisplayName("显式 null 字段值正确反序列化为 null")
    void explicitNullFieldsAreHandled() throws Exception {
        String jsonWithNulls = "{\"type\":null,\"targetUserId\":null,\"payload\":null,\"sourceInstanceId\":null}";

        RouteMessage msg = objectMapper.readValue(jsonWithNulls, RouteMessage.class);

        assertNull(msg.getType());
        assertNull(msg.getTargetUserId());
        assertNull(msg.getPayload());
        assertNull(msg.getSourceInstanceId());
    }
}
