package com.zw.sserver.application.message;

import com.zw.sserver.model.dto.ChatMsgDTO;

/**
 * 发送私聊消息命令。
 */
public class SendPrivateMessageCommand {

    private final Long authedUid;
    private final ChatMsgDTO message;

    public SendPrivateMessageCommand(Long authedUid, ChatMsgDTO message) {
        this.authedUid = authedUid;
        this.message = message;
    }

    public Long getAuthedUid() {
        return authedUid;
    }

    public ChatMsgDTO getMessage() {
        return message;
    }
}
