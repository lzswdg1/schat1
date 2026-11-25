package com.zw.sserver.application.message;

import com.zw.sserver.domain.message.service.GroupMessagePolicy;
import com.zw.sserver.domain.message.service.PrivateMessagePolicy;
import com.zw.sserver.model.dto.ChatMsgDTO;
import com.zw.sserver.model.dto.GroupMsgDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageContentPolicyTest {

    private java.util.List<Runnable> validations(String clientId, String content, String quote) {
        var privateMessage = new ChatMsgDTO();
        privateMessage.setClientMsgId(clientId);
        privateMessage.setToId(2L);
        privateMessage.setMsgType("TEXT");
        privateMessage.setContent(content);
        privateMessage.setQuoteContent(quote);
        var groupMessage = new GroupMsgDTO();
        groupMessage.setClientMsgId(clientId);
        groupMessage.setGroupId(10L);
        groupMessage.setMsgType("GROUP_TEXT");
        groupMessage.setContent(content);
        groupMessage.setQuoteContent(quote);
        return java.util.List.of(
                () -> new PrivateMessagePolicy().validate(1L, privateMessage),
                () -> new GroupMessagePolicy().validate(1L, groupMessage));
    }

    @Test
    void privateAndGroupMessagesAcceptBoundaryLengths() {
        validations("x".repeat(64), "x".repeat(5000), "x".repeat(255))
                .forEach(validation -> assertDoesNotThrow(validation::run));
    }

    @Test
    void privateAndGroupMessagesRejectOversizedOrEmptyFields() {
        for (String content : new String[] { null, "", "  ", "x".repeat(5001) }) {
            validations("client", content, null)
                    .forEach(validation -> assertThrows(IllegalArgumentException.class, validation::run));
        }
        validations("x".repeat(65), "valid", null)
                .forEach(validation -> assertThrows(IllegalArgumentException.class, validation::run));
        validations("client", "valid", "x".repeat(256))
                .forEach(validation -> assertThrows(IllegalArgumentException.class, validation::run));
    }

    @Test
    void unknownPrivateMessageTypesCannotInflateTheSerializedPayload() {
        var message = new ChatMsgDTO();
        message.setToId(2L);
        message.setClientMsgId("client");
        message.setContent("valid");
        for (String type : new String[] { null, "SCRIPT", "x".repeat(5001) }) {
            message.setMsgType(type);
            assertThrows(IllegalArgumentException.class, () -> new PrivateMessagePolicy().validate(1L, message));
        }
        for (String type : new String[] { "TEXT", "IMAGE", "FILE", "text" }) {
            message.setMsgType(type);
            assertDoesNotThrow(() -> new PrivateMessagePolicy().validate(1L, message));
        }
    }
}
