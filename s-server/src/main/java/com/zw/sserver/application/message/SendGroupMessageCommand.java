package com.zw.sserver.application.message;

import com.zw.sserver.model.dto.GroupMsgDTO;

/**
 * 发送群聊消息命令。
 */
public class SendGroupMessageCommand {

    private final Long authedUid;
    private final GroupMsgDTO message;

    public SendGroupMessageCommand(Long authedUid, GroupMsgDTO message) {
        this.authedUid = authedUid;
        this.message = message;
    }

    public Long getAuthedUid() {
        return authedUid;
    }

    public GroupMsgDTO getMessage() {
        return message;
    }
}
